package ani.dantotsu.extension.novel.wtrlab

import androidx.preference.PreferenceScreen
import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.ConfigurableNovelSource
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.SafeHttp
import ani.dantotsu.parsers.ShowResponse
import com.lagradost.nicehttp.Requests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

@Suppress("unused")
class WtrLab : NovelInterface, ConfigurableNovelSource {

    val name = "WtrLab"
    val saveName = "wtrlab"

    private var currentHost = "https://wtr-lab.com"
    private val imgBaseUrl = "https://wtr-lab.com/api/v2/img"
    private val lang = "en"

    private val json = Json { ignoreUnknownKeys = true }
    private val titleTemplateRegex = Regex("""%\{([^|{}]*)\|[^{}]*\}""")

    private var cachedBuildId: String? = null

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No preferences required
    }

    private suspend fun getBuildId(client: Requests): String {
        cachedBuildId?.let { return it }

        val res = SafeHttp.get(client, "$currentHost/$lang/novel-finder", defaultHeaders)
        val doc = res.document
        val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
        if (nextData != null) {
            val buildId = runCatching {
                json.parseToJsonElement(nextData).jsonObject["buildId"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!buildId.isNullOrBlank()) {
                cachedBuildId = buildId
                return buildId
            }
        }

        // Fallback: search for buildId in scripts
        val scriptMatch = Regex("""/_next/static/([^/]+)/_buildManifest\.js""").find(res.text)
        val extracted = scriptMatch?.groupValues?.get(1) ?: "dev"
        cachedBuildId = extracted
        return extracted
    }

    private fun resolveTitleTemplate(title: String): String =
        title.replace(titleTemplateRegex) { it.groupValues[1] }

    private fun transformImageUrl(imageUrl: String?): String? {
        if (imageUrl.isNullOrBlank()) return null
        if (imageUrl.startsWith(imgBaseUrl)) return imageUrl

        val path = when {
            imageUrl.contains("img.wtr-lab.com/cdn/") -> imageUrl.substringAfter("img.wtr-lab.com/cdn/")
            imageUrl.contains("wtrimg/") -> imageUrl.substringAfter("wtrimg/")
            imageUrl.startsWith("/") -> imageUrl.removePrefix("/")
            else -> return imageUrl
        }

        val s3Path = "s3://wtrimg/${path.substringBefore('?')}"
        val encodedPath = URLEncoder.encode(s3Path, "UTF-8")
        return "$imgBaseUrl?src=$encodedPath&w=344"
    }

    override suspend fun search(query: String, client: Requests): List<ShowResponse> {
        val buildId = getBuildId(client)
        val trimmed = query.trim()
        val url = if (trimmed.isBlank() || trimmed == "*") {
            "$currentHost/_next/data/$buildId/$lang/novel-finder.json?locale=$lang&page=1"
        } else {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            "$currentHost/_next/data/$buildId/$lang/novel-finder.json?text=$encoded&locale=$lang&page=1"
        }

        val res = SafeHttp.get(client, url, defaultHeaders)
        if (!res.isSuccessful || res.text.isBlank()) return emptyList()

        val root = runCatching {
            json.parseToJsonElement(res.text).jsonObject
        }.getOrNull() ?: return emptyList()

        val pageProps = root["pageProps"]?.jsonObject
            ?: root["props"]?.jsonObject?.get("pageProps")?.jsonObject
            ?: return emptyList()

        val series = (pageProps["series"] as? JsonArray)
            ?: (pageProps["serie"]?.jsonObject?.get("series") as? JsonArray)
            ?: return emptyList()

        return series.mapNotNull { element ->
            val obj = element.jsonObject
            val rawId = obj["raw_id"]?.jsonPrimitive?.intOrNull ?: obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: "novel-$rawId"
            val data = obj["data"]?.jsonObject ?: obj

            val rawTitle = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Novel $rawId" }
            val title = resolveTitleTemplate(rawTitle)
            val coverUrl = transformImageUrl(data["image"]?.jsonPrimitive?.contentOrNull) ?: DEFAULT_COVER
            val author = data["author"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val desc = data["description"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val novelUrl = "$currentHost/$lang/novel/$rawId/$slug"
            ShowResponse(
                name = title,
                link = novelUrl,
                coverUrl = coverUrl,
                extra = mutableMapOf(
                    "rawId" to rawId.toString(),
                    "slug" to slug,
                    "title" to title,
                    "author" to author,
                    "description" to desc
                )
            )
        }
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book {
        val rawIdMatch = Regex("""(?:novel/|serie-)(\d+)""").find(link)
        val rawId = extra?.get("rawId")?.toIntOrNull()
            ?: rawIdMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        val slug = extra?.get("slug")
            ?: Regex("""(?:novel/|serie-)\d+/([^/?#]+)""").find(link)?.groupValues?.get(1)
            ?: "novel"

        val title = extra?.get("title") ?: "WtrLab Novel $rawId"
        var coverUrl = DEFAULT_COVER
        var description = extra?.get("description")

        // Fetch details from Next.js data route if possible
        if (rawId > 0) {
            val buildId = runCatching { getBuildId(client) }.getOrNull()
            if (!buildId.isNullOrBlank()) {
                val detailsUrl = "$currentHost/_next/data/$buildId/$lang/novel/$rawId/$slug.json?locale=$lang&raw_id=$rawId&serie_slug=$slug"
                val res = runCatching { SafeHttp.get(client, detailsUrl, defaultHeaders) }.getOrNull()
                if (res != null && res.isSuccessful) {
                    val root = runCatching { json.parseToJsonElement(res.text).jsonObject }.getOrNull()
                    val pageProps = root?.get("pageProps")?.jsonObject
                    val serieData = pageProps?.get("serie")?.jsonObject?.get("serie_data")?.jsonObject
                    val data = serieData?.get("data")?.jsonObject
                    if (data != null) {
                        transformImageUrl(data["image"]?.jsonPrimitive?.contentOrNull)?.let { coverUrl = it }
                        data["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { description = it }
                    }
                }
            }
        }

        // Fetch chapter list from API
        val chapters = mutableListOf<FileUrl>()
        if (rawId > 0) {
            val chaptersUrl = "$currentHost/api/chapters/$rawId?start=1&end=500"
            val chRes = runCatching { SafeHttp.get(client, chaptersUrl, defaultHeaders) }.getOrNull()
            if (chRes != null && chRes.isSuccessful) {
                val jsonResult = runCatching { json.parseToJsonElement(chRes.text).jsonObject }.getOrNull()
                val chArray = jsonResult?.get("chapters") as? JsonArray
                chArray?.forEach { el ->
                    val obj = el.jsonObject
                    val order = obj["order"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val chTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter $order"
                    val chUrl = "$currentHost/$lang/novel/$rawId/chapter-$order"
                    chapters.add(FileUrl(chUrl, mapOf("X-Chapter-Name" to chTitle)))
                }
            }
        }

        if (chapters.isEmpty()) {
            chapters.add(FileUrl(link, mapOf("X-Chapter-Name" to title)))
        }

        return Book(
            name = title,
            img = FileUrl(coverUrl, defaultHeaders),
            description = description,
            links = chapters
        )
    }

    companion object {
        private const val DEFAULT_COVER = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"
    }
}
