package ani.dantotsu.extension.novel.libgen

import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.FileUrl
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.ShowResponse
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import org.jsoup.nodes.Element

@Suppress("unused")
class Libgen : NovelInterface {

    val name = "Libgen"
    val saveName = "libgen"

    // Active mirrors tested and maintained
    private val mirrors = listOf(
        "https://libgen.la",
        "https://libgen.bz",
        "https://libgen.gl",
        "https://libgen.im",
        "https://libgen.is",
        "https://libgen.rs"
    )

    private var currentHost = mirrors[0]

    private val volumeRegex = Regex("vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private suspend fun fetchWithMirrorFallback(pathOrUrl: String, client: Requests): NiceResponse {
        val path = if (pathOrUrl.startsWith("http")) {
            val stripped = pathOrUrl.replace(Regex("^https?://[^/]+"), "")
            if (stripped.isBlank()) "/" else stripped
        } else {
            if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        }

        var lastException: Exception? = null
        for (mirror in mirrors) {
            try {
                val fullUrl = "$mirror$path"
                val res = ani.dantotsu.parsers.WebViewResolver.getWithBypass(fullUrl, defaultHeaders, client, defaultHeaders["User-Agent"] ?: "")
                if (res.isSuccessful) {
                    currentHost = mirror
                    return res
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("All Libgen mirrors failed to connect")
    }

    private fun parseTableStripedRow(tr: Element): ShowResponse? {
        val tds = tr.select("td")
        if (tds.size < 8) return null

        val format = tds.getOrNull(7)?.text().orEmpty().lowercase()
        if (!format.contains("epub")) return null

        val col0 = tds[0]
        val titleLink = col0.selectFirst("a[href*=edition.php]")
            ?: col0.selectFirst("a[href*=book/]")
            ?: col0.selectFirst("a")

        val name = titleLink?.text()?.ifBlank { null }
            ?: col0.text().substringBefore("#").trim().ifBlank { null }
            ?: "Unknown Title"

        val href = titleLink?.attr("href").orEmpty()
        val fullLink = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$currentHost$href"
            href.isNotBlank() -> "$currentHost/$href"
            else -> {
                // If no detail link, check mirror links in col 8
                val mirrorHref = tds.getOrNull(8)?.selectFirst("a")?.attr("href").orEmpty()
                if (mirrorHref.startsWith("http")) mirrorHref else "$currentHost$mirrorHref"
            }
        }
        if (fullLink.isBlank()) return null

        val author = tds.getOrNull(1)?.text().orEmpty()
        val publisher = tds.getOrNull(2)?.text().orEmpty()
        val year = tds.getOrNull(3)?.text().orEmpty()
        val lang = tds.getOrNull(4)?.text().orEmpty()
        val size = tds.getOrNull(6)?.text().orEmpty()

        val extra = mutableMapOf<String, String>()
        if (author.isNotBlank()) extra["0"] = author
        if (year.isNotBlank() || publisher.isNotBlank()) extra["1"] = listOf(year, publisher).filter { it.isNotBlank() }.joinToString(" • ")
        val descParts = listOf(format.uppercase(), size, lang).filter { it.isNotBlank() }
        if (descParts.isNotEmpty()) extra["2"] = descParts.joinToString(" • ")

        // Store direct download mirrors in extra for instant loadBook resolution
        tds.getOrNull(8)?.select("a")?.forEachIndexed { idx, a ->
            val mHref = a.attr("href")
            val fullM = if (mHref.startsWith("http")) mHref else "$currentHost$mHref"
            extra["mirror_$idx"] = "${a.text()}|$fullM"
        }

        return ShowResponse(name, fullLink, defaultImage, extra = extra)
    }

    private fun parseClassicCatalogRow(tr: Element): ShowResponse? {
        val tds = tr.select("td")
        if (tds.size < 5) return null

        var name: String? = null
        var link: String? = null
        val extra = mutableMapOf<String, String>()

        for ((index, td) in tds.withIndex()) {
            when (index) {
                0 -> extra["0"] = td.select("a").text()
                1 -> extra["1"] = td.text()
                2 -> {
                    name = td.selectFirst("a")?.text()
                    link = td.selectFirst("a")?.attr("href")
                }
                3 -> extra["2"] = td.text() + " • "
                4 -> {
                    val ext = td.text()
                    extra["2"] = (extra["2"].orEmpty()) + ext
                    if (!ext.contains("epub", ignoreCase = true)) {
                        return null
                    }
                }
            }
        }

        if (name == null || link == null) return null
        val fullUrl = if (link.startsWith("http")) link else "$currentHost$link"
        return ShowResponse(name, fullUrl, defaultImage, extra = extra)
    }

    override suspend fun search(query: String, client: Requests): List<ShowResponse> {
        val q = query.substringAfter("!$").replace("-", " ").trim()
        val encodedQuery = java.net.URLEncoder.encode(q, "UTF-8")
        val searchPath = "/index.php?req=$encodedQuery"

        val response = try {
            fetchWithMirrorFallback(searchPath, client)
        } catch (_: Exception) {
            return emptyList()
        }

        val doc = response.document

        // Attempt 1: Modern table-striped
        val stripedRows = doc.select("table.table-striped tr")
        val results = mutableListOf<ShowResponse>()

        if (stripedRows.size > 1) {
            for (tr in stripedRows.drop(1)) {
                val parsed = parseTableStripedRow(tr)
                if (parsed != null && results.none { it.link == parsed.link }) {
                    results.add(parsed)
                }
            }
        }

        // Attempt 2: Classic catalog table
        if (results.isEmpty()) {
            val catalogRows = doc.select("table.catalog tr")
            for (tr in catalogRows) {
                val parsed = parseClassicCatalogRow(tr)
                if (parsed != null && results.none { it.link == parsed.link }) {
                    results.add(parsed)
                }
            }
        }

        return if (query.startsWith("!$")) results.sortByVolume(q) else results
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book {
        val fileUrls = mutableListOf<FileUrl>()

        // 1. Check if direct mirrors were cached in extra metadata
        extra?.filterKeys { it.startsWith("mirror_") }?.values?.forEach { valStr ->
            val parts = valStr.split("|", limit = 2)
            val label = parts.getOrNull(0) ?: "Mirror"
            val mUrl = parts.getOrNull(1) ?: return@forEach
            val resolved = LinkExtractor(mUrl, currentHost, client).extractLinks()
            for ((srv, resUrl) in resolved) {
                if (fileUrls.none { it.url == resUrl }) {
                    fileUrls.add(FileUrl(resUrl, mapOf("X-Chapter-Name" to "$label ($srv)")))
                }
            }
        }

        // 2. If the link is an edition/record page, scrape full details
        var name = "Unknown Title"
        var img = defaultImage
        var description = ""

        try {
            val response = fetchWithMirrorFallback(link, client)
            val doc = response.document

            val cover = doc.selectFirst("div.record_side img")?.attr("src")
                ?: doc.selectFirst("img[src*=/covers/]")?.attr("src")
            if (!cover.isNullOrBlank()) {
                img = if (cover.startsWith("http")) cover else "$currentHost$cover"
            }

            name = doc.selectFirst("td.record_title")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("table.record tr td")?.text()
                ?: name

            val mirrorLinks = doc.select("ul.record_mirrors a, table.record a[href*=get.php], div.mirrors a")
                .map { it.attr("href") }
                .filter { ln ->
                    !ln.contains("Fast") &&
                            !ln.contains("onion") &&
                            !ln.contains("/datasets") &&
                            !ln.contains("1lib") &&
                            !ln.contains("torrent")
                }

            for (mLink in mirrorLinks) {
                val resolved = LinkExtractor(mLink, currentHost, client).extractLinks()
                for ((srv, resUrl) in resolved) {
                    if (fileUrls.none { it.url == resUrl }) {
                        fileUrls.add(FileUrl(resUrl, mapOf("X-Chapter-Name" to srv)))
                    }
                }
            }
        } catch (_: Exception) {
            // If fetching detail page failed, fall back to link itself
            if (fileUrls.isEmpty() && link.startsWith("http")) {
                fileUrls.add(FileUrl(link, mapOf("X-Chapter-Name" to "Direct Link")))
            }
        }

        return Book(name, FileUrl(img), description, fileUrls)
    }

    private class LinkExtractor(
        private val rawUrl: String,
        private val hostUrl: String,
        private val client: Requests
    ) {
        suspend fun extractLinks(): List<Pair<String, String>> {
            val url = if (rawUrl.startsWith("/")) "$hostUrl$rawUrl" else rawUrl
            return try {
                when {
                    url.contains("library.lol") -> extractLibraryLol(url)
                    url.contains("annas-archive") -> listOf("Anna's Archive Mirror" to url)
                    url.contains("get.php") || url.contains("ads.php") -> extractGetPhp(url)
                    else -> listOf("Libgen Mirror" to url)
                }
            } catch (_: Exception) {
                if (url.startsWith("http")) listOf("Libgen Mirror" to url) else emptyList()
            }
        }

        private suspend fun extractLibraryLol(url: String): List<Pair<String, String>> {
            return try {
                val res = client.get(url)
                val directA = res.document.selectFirst("div#download a[href]")
                val directUrl = directA?.attr("href")
                if (!directUrl.isNullOrBlank() && !directUrl.contains("localhost")) {
                    listOf("Library.lol Mirror (Direct)" to directUrl)
                } else {
                    listOf("Library.lol Mirror" to url)
                }
            } catch (_: Exception) {
                listOf("Library.lol Mirror" to url)
            }
        }

        private suspend fun extractGetPhp(url: String): List<Pair<String, String>> {
            return try {
                if (url.contains("ads.php")) {
                    val res = client.get(url)
                    val directA = res.document.selectFirst("table#main a[href*=.php]")
                        ?: res.document.selectFirst("div#download a[href]")
                    val directUrl = directA?.attr("href")
                    if (!directUrl.isNullOrBlank()) {
                        val full = if (directUrl.startsWith("http")) directUrl else url.substringBeforeLast("/") + "/" + directUrl.removePrefix("/")
                        listOf("Direct Download" to full)
                    } else {
                        listOf("Libgen Download" to url)
                    }
                } else {
                    listOf("Libgen Direct" to url)
                }
            } catch (_: Exception) {
                listOf("Libgen Download" to url)
            }
        }
    }

    private fun List<ShowResponse>.sortByVolume(query: String): List<ShowResponse> {
        val sorted = groupBy { res ->
            val match = volumeRegex.find(res.name)?.groupValues
                ?.firstOrNull { it.isNotEmpty() }
                ?.substringAfter(" ")
                ?.toDoubleOrNull() ?: Double.MAX_VALUE
            match
        }.toSortedMap().values

        val volumes = sorted.map { showList ->
            val nonDefaultCoverShows = showList.filter { it.coverUrl.url != defaultImage }
            nonDefaultCoverShows.firstOrNull { it.name.contains(query, ignoreCase = true) }
                ?: nonDefaultCoverShows.firstOrNull()
                ?: showList.first()
        }
        val remainingShows = sorted.flatten() - volumes.toSet()
        return volumes + remainingShows
    }
}
