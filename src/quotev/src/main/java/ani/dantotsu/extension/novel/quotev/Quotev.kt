package ani.dantotsu.extension.novel.quotev

import androidx.preference.PreferenceScreen
import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.ConfigurableNovelSource
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.SafeHttp
import ani.dantotsu.parsers.ShowResponse
import com.lagradost.nicehttp.Requests
import org.jsoup.Jsoup
import java.net.URLEncoder

@Suppress("unused")
class Quotev : NovelInterface, ConfigurableNovelSource {

    val name = "Quotev"
    val saveName = "quotev"

    private var currentHost = "https://www.quotev.com"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1"
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No preferences currently needed for Quotev
    }

    override suspend fun search(query: String, client: Requests): List<ShowResponse> {
        val trimmed = query.trim()
        val url = if (trimmed.isBlank() || trimmed == "*") {
            "$currentHost/stories?v=users"
        } else {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            "$currentHost/search/$encodedQuery"
        }

        val res = SafeHttp.get(client, url, defaultHeaders)
        if (!res.isSuccessful || res.text.isBlank()) return emptyList()

        val doc = res.document
        val cards = doc.select("div.quiz[data-quizid]")
        return cards.mapNotNull { card ->
            val linkElem = card.selectFirst("h2 a[href*=/story/]") ?: return@mapNotNull null
            val href = linkElem.attr("abs:href").ifBlank {
                val rel = linkElem.attr("href")
                if (rel.startsWith("http")) rel else "$currentHost$rel"
            }
            val title = linkElem.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val cover = card.selectFirst("img.logo")?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_COVER
            val author = card.selectFirst("span.author a")?.text().orEmpty().trim()
            val desc = card.selectFirst("div.descr")?.text().orEmpty().trim()

            ShowResponse(
                name = title,
                link = href,
                coverUrl = cover,
                extra = mutableMapOf(
                    "title" to title,
                    "author" to author,
                    "description" to desc
                )
            )
        }
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book {
        val targetUrl = if (link.startsWith("http://") || link.startsWith("https://")) link else "$currentHost$link"
        val res = SafeHttp.get(client, targetUrl, defaultHeaders)

        val doc = res.document
        val title = doc.selectFirst("#quizHeaderTitle h1")?.text()?.trim()
            ?: extra?.get("title")
            ?: doc.title().trim()

        val cover = doc.selectFirst("img.logo")?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_COVER

        val author = doc.selectFirst(".quizAuthorList a")?.text().orEmpty().trim()
        val descHtml = doc.selectFirst("#qdesct")?.html().orEmpty()
        val description = if (descHtml.isNotBlank()) {
            Jsoup.parseBodyFragment(descHtml.replace("<br>", "\n").replace("<p>", "\n")).text()
        } else {
            extra?.get("description").orEmpty()
        }

        // Story base path without trailing page numbers
        val storyBasePath = targetUrl.substringBeforeLast("?").removeSuffix("/")
        val cleanBasePath = if (Regex("""/\d+$""").containsMatchIn(storyBasePath)) {
            storyBasePath.substringBeforeLast("/")
        } else {
            storyBasePath
        }

        val options = doc.select("select[name=rid] option[value]")
        val chapters = if (options.isNotEmpty()) {
            options.mapNotNull { opt ->
                val startPage = opt.attr("value").trim()
                val chTitle = opt.text().trim().ifBlank { "Chapter $startPage" }
                val chUrl = "$cleanBasePath/$startPage"
                FileUrl(chUrl, mapOf("X-Chapter-Name" to chTitle))
            }
        } else {
            val singleTitle = doc.selectFirst("#quizSubtitle")?.text()?.trim() ?: "Chapter 1"
            listOf(FileUrl("$cleanBasePath/1", mapOf("X-Chapter-Name" to singleTitle)))
        }

        return Book(
            name = title,
            img = FileUrl(cover, defaultHeaders),
            description = description.ifBlank { if (author.isNotBlank()) "Author: $author" else null },
            links = chapters
        )
    }

    companion object {
        private const val DEFAULT_COVER = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"
    }
}
