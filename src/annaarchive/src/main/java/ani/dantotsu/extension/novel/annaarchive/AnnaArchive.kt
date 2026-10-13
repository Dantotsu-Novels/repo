package ani.dantotsu.extension.novel.annaarchive

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.ConfigurableNovelSource
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.WebViewResolver
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Element

@Suppress("unused")
class AnnaArchive : NovelInterface, ConfigurableNovelSource {

    val name = "Anna's Archive"
    val saveName = "anna"

    // Official active mirrors and proxies
    private val mirrors = listOf(
        "https://annas-archive.li",
        "https://annas-archive.se",
        "https://annas-archive.org",
        "https://annas-archive.gl",
        "https://annas-archive.pk",
        "https://annas-archive.gd"
    )

    private var currentHost = mirrors[0]

    private val volumeRegex = Regex("vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        val mirrorPref = ListPreference(context).apply {
            key = PREF_MIRROR_KEY
            title = "Active Mirror / Base URL"
            summary = "%s"
            entries = mirrors.toTypedArray()
            entryValues = mirrors.toTypedArray()
            setDefaultValue(mirrors[0])
            setOnPreferenceChangeListener { _, newValue ->
                currentHost = newValue as String
                true
            }
        }
        screen.addPreference(mirrorPref)
    }

    private fun getActiveMirrors(): List<String> {
        val prefMirror = try {
            getSourcePreferences().getString(PREF_MIRROR_KEY, null)
        } catch (_: Throwable) {
            null
        }
        return if (!prefMirror.isNullOrBlank() && prefMirror in mirrors) {
            listOf(prefMirror) + (mirrors - prefMirror)
        } else {
            mirrors
        }
    }

    private suspend fun fetchWithMirrorFallback(pathOrUrl: String, client: Requests): NiceResponse {
        val path = if (pathOrUrl.startsWith("http")) {
            val stripped = pathOrUrl.replace(Regex("^https?://[^/]+"), "")
            if (stripped.isBlank()) "/" else stripped
        } else {
            if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        }

        var lastException: Exception? = null
        for (mirror in getActiveMirrors()) {
            try {
                val fullUrl = "$mirror$path"
                val res = withTimeout(7000L) {
                    WebViewResolver.getWithBypass(fullUrl, defaultHeaders, client, userAgent)
                }
                if (res.isSuccessful) {
                    currentHost = mirror
                    return res
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("All Anna's Archive mirrors failed to connect")
    }

    private fun parseShowResponse(element: Element): ShowResponse? {
        val linkElem = if (element.tagName() == "a") {
            element
        } else {
            element.selectFirst("a[href*=/md5/], a[href*=/books/]") ?: return null
        }

        val href = linkElem.attr("href")
        if (!href.contains("/md5/") && !href.contains("/books/")) return null

        val fullCardText = element.text()
        if (!fullCardText.contains("epub", ignoreCase = true) && !element.attr("class").contains("epub", ignoreCase = true)) {
            val hasEpub = element.select("div, span").any { it.text().contains("epub", ignoreCase = true) }
            if (!hasEpub) return null
        }

        val name = element.selectFirst("h3")?.text()?.ifBlank { null }
            ?: element.selectFirst("div.line-clamp-2")?.text()?.ifBlank { null }
            ?: element.selectFirst("div.font-bold")?.text()?.ifBlank { null }
            ?: element.selectFirst(".text-lg")?.text()?.ifBlank { null }
            ?: linkElem.ownText().ifBlank { null }
            ?: element.ownText().ifBlank { "Unknown Title" }

        var img = element.selectFirst("img")?.attr("src").orEmpty()
        if (img.startsWith("/")) {
            img = "$currentHost$img"
        }
        if (img.isBlank()) {
            img = defaultImage
        }

        val extra = mutableMapOf<String, String>()
        val author = element.selectFirst("div.italic")?.text()
            ?: element.selectFirst("div[class*=text-violet]")?.text()
            ?: ""
        if (author.isNotBlank()) extra["0"] = author

        val metaLines = element.select("div[class*=text-xs], span[class*=text-xs], div[class*=text-sm]").map { it.text() }.filter { it.isNotBlank() }
        if (metaLines.isNotEmpty()) {
            extra["1"] = metaLines.first()
            if (metaLines.size > 1) {
                extra["2"] = metaLines.drop(1).joinToString(" • ")
            }
        }

        val targetUrl = if (href.startsWith("http")) href else "$currentHost$href"
        return ShowResponse(name, targetUrl, img, extra = extra)
    }

    override suspend fun search(query: String, client: Requests): List<ShowResponse> {
        val q = query.substringAfter("!$").replace("-", " ").trim()
        val encodedQuery = java.net.URLEncoder.encode(q, "UTF-8")
        val searchPath = "/search?ext=epub&q=$encodedQuery"

        val response = try {
            fetchWithMirrorFallback(searchPath, client)
        } catch (_: Exception) {
            return emptyList()
        }

        val doc = response.document

        // Attempt 1: Modern card selectors with /md5/ or /books/ links
        val candidateElements = doc.select("a[href*=/md5/], a[href*=/books/]")
        val results = mutableListOf<ShowResponse>()

        for (el in candidateElements) {
            val parentEl: Element? = el.parent()
            val container: Element = if (parentEl != null &&
                (parentEl.select("img").isNotEmpty() || parentEl.select("div.italic").isNotEmpty() || parentEl.hasClass("flex"))
            ) parentEl else el
            val parsed = parseShowResponse(container)
            if (parsed != null && results.none { it.link == parsed.link }) {
                results.add(parsed)
            }
        }

        // Fallback: search general result rows
        if (results.isEmpty()) {
            val fallbackRows = doc.select("main div[class*=border], div[class*=h-[125]], div.flex.gap-\\[18px\\], div.rounded-lg.shadow")
            for (row in fallbackRows) {
                val parsed = parseShowResponse(row)
                if (parsed != null && results.none { it.link == parsed.link }) {
                    results.add(parsed)
                }
            }
        }

        return if (query.startsWith("!$")) results.sortByVolume(q) else results
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book {
        val response = fetchWithMirrorFallback(link, client)
        val doc = response.document
        val main = doc.selectFirst("main") ?: doc.body()

        val name = main.selectFirst("div.text-3xl")?.text()?.substringBefore("\uD83D\uDD0D")
            ?: main.selectFirst("h1")?.text()
            ?: main.selectFirst("div.font-bold")?.text()
            ?: "Unknown Title"

        var img = main.selectFirst("img")?.attr("src").orEmpty()
        if (img.startsWith("/")) img = "$currentHost$img"
        if (img.isBlank()) img = defaultImage

        val description = main.selectFirst("div.js-md5-top-box-description")?.text()
            ?: main.selectFirst("div[class*=description]")?.text()
            ?: main.selectFirst("p.mt-2")?.text()

        // Extract download candidates
        val downloadAnchors = main.select("a[href*=/slow_download/], a.js-download-link, a[href*=library.lol], a[href*=libgen], a[href*=ipfs], a[href*=/download]")
            .filter { a ->
                val href = a.attr("href")
                !href.contains("onion") &&
                        !href.contains("torrent") &&
                        !href.contains("/datasets") &&
                        !href.contains("1lib") &&
                        !href.contains("/account")
            }

        val fileUrls = mutableListOf<FileUrl>()

        val resolvedPairs = coroutineScope {
            downloadAnchors.map { anchor ->
                async(Dispatchers.IO) {
                    val rawHref = anchor.attr("href")
                    LinkExtractor(rawHref, currentHost, client).extractLinks()
                }
            }.awaitAll().flatten()
        }

        for ((serverLabel, resolvedUrl) in resolvedPairs) {
            if (fileUrls.none { it.url == resolvedUrl }) {
                fileUrls.add(FileUrl(resolvedUrl, mapOf("X-Chapter-Name" to serverLabel)))
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
                    url.contains("libgen") -> extractLibgen(url)
                    url.contains("slow_download") -> extractSlowDownload(url)
                    url.contains("ipfs") || url.contains("cloudflare-ipfs") -> listOf("IPFS Gateway" to url)
                    else -> listOf("Direct Download" to url)
                }
            } catch (_: Exception) {
                if (url.startsWith("http")) listOf("Download Mirror" to url) else emptyList()
            }
        }

        private suspend fun extractLibraryLol(url: String): List<Pair<String, String>> {
            return try {
                val res = ani.dantotsu.parsers.SafeHttp.get(client, url)
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

        private suspend fun extractLibgen(url: String): List<Pair<String, String>> {
            return try {
                val res = ani.dantotsu.parsers.SafeHttp.get(client, url)
                val doc = res.document
                val directLink = doc.selectFirst("table#main a[href*=.php]")?.attr("href")
                    ?: doc.selectFirst("div#download a[href]")?.attr("href")

                if (!directLink.isNullOrBlank()) {
                    val finalUrl = if (directLink.startsWith("http")) directLink else url.substringBeforeLast("/") + "/" + directLink.removePrefix("/")
                    listOf("Libgen Mirror (Direct)" to finalUrl)
                } else {
                    listOf("Libgen Mirror" to url)
                }
            } catch (_: Exception) {
                listOf("Libgen Mirror" to url)
            }
        }

        private suspend fun extractSlowDownload(url: String): List<Pair<String, String>> {
            return try {
                val res = ani.dantotsu.parsers.SafeHttp.get(client, url)
                val doc = res.document
                val downloadLink = doc.select("a[href]").mapNotNull { it.attr("href") }
                    .firstOrNull { it.contains("download") && !it.contains("localhost") && !it.contains("annas-archive") }
                if (!downloadLink.isNullOrBlank()) {
                    listOf("Anna's Archive Partner" to downloadLink)
                } else {
                    listOf("Slow Download" to url)
                }
            } catch (_: Exception) {
                listOf("Slow Download" to url)
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

    companion object {
        private const val PREF_MIRROR_KEY = "mirror_base_url"
    }
}
