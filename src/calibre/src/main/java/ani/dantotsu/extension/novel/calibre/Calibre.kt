package ani.dantotsu.extension.novel.calibre

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.ConfigurableNovelSource
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.SafeHttp
import ani.dantotsu.parsers.ShowResponse
import com.lagradost.nicehttp.Requests
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64

@Suppress("unused")
class Calibre : NovelInterface, ConfigurableNovelSource {

    val name = "Calibre"
    val saveName = "calibre"

    private var currentHost = DEFAULT_BASE_URL

    private val json = Json { ignoreUnknownKeys = true }

    private fun getBaseUrl(): String {
        val prefs = getSourcePreferences()
        val url = prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL)?.trim().orEmpty()
        val cleaned = if (url.isBlank()) DEFAULT_BASE_URL else url
        currentHost = cleaned.removeSuffix("/")
        return currentHost
    }

    private fun getHeaders(): Map<String, String> {
        val prefs = getSourcePreferences()
        val headers = mutableMapOf<String, String>()
        val user = prefs.getString(PREF_USER, "").orEmpty().trim()
        val pass = prefs.getString(PREF_PASS, "").orEmpty().trim()
        if (user.isNotEmpty()) {
            val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
            headers["Authorization"] = "Basic $token"
        }
        return headers
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = PREF_BASE_URL
            title = "Calibre Server URL"
            summary = "URL of your Calibre content server (e.g. http://192.168.1.100:8080)"
            dialogTitle = "Server URL"
            setDefaultValue(DEFAULT_BASE_URL)
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_USER
            title = "Username"
            summary = "Optional, only if the server requires login"
            dialogTitle = "Username"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_PASS
            title = "Password"
            summary = "Optional, only if the server requires login"
            dialogTitle = "Password"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    override suspend fun search(query: String, client: Requests): List<ShowResponse> {
        val baseUrl = getBaseUrl()
        val headers = getHeaders()

        val trimmed = query.trim()
        val url = if (trimmed.isBlank() || trimmed == "*") {
            "$baseUrl/ajax/search?num=30&offset=0&sort=title&sort_order=asc"
        } else {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            "$baseUrl/ajax/search?query=$encodedQuery&num=30&offset=0&sort=title&sort_order=asc"
        }

        val searchRes = SafeHttp.get(client, url, headers)
        if (!searchRes.isSuccessful || searchRes.text.isBlank()) return emptyList()

        val searchData = runCatching {
            json.decodeFromString<SearchResponse>(searchRes.text)
        }.getOrNull() ?: return emptyList()

        if (searchData.bookIds.isEmpty()) return emptyList()

        val booksRes = SafeHttp.get(client, "$baseUrl/ajax/books?ids=${searchData.bookIds.joinToString(",")}", headers)
        if (!booksRes.isSuccessful || booksRes.text.isBlank()) return emptyList()

        val booksMap = runCatching {
            json.decodeFromString<Map<String, BookMetadata>>(booksRes.text)
        }.getOrNull() ?: return emptyList()

        return searchData.bookIds.mapNotNull { id ->
            val book = booksMap[id.toString()] ?: return@mapNotNull null
            val coverUrl = "$baseUrl/get/cover/$id"
            val author = book.authors.joinToString(", ")
            ShowResponse(
                name = book.title,
                link = "$baseUrl/ajax/book/$id",
                coverUrl = coverUrl,
                extra = mutableMapOf(
                    "id" to id.toString(),
                    "title" to book.title,
                    "author" to author
                )
            )
        }
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book {
        val baseUrl = getBaseUrl()
        val headers = getHeaders()

        val id = extra?.get("id")
            ?: Regex("""/book/(\d+)""").find(link)?.groupValues?.get(1)
            ?: link.substringAfterLast("/").takeIf { it.all { c -> c.isDigit() } }
            ?: ""

        val bookUrl = if (link.startsWith("http://") || link.startsWith("https://")) link else "$baseUrl/ajax/book/$id"
        val response = SafeHttp.get(client, bookUrl, headers)

        val metadata = runCatching {
            json.decodeFromString<BookMetadata>(response.text)
        }.getOrNull()

        val title = metadata?.title ?: extra?.get("title") ?: "Calibre Book $id"
        val coverUrl = "$baseUrl/get/cover/$id"
        val desc = metadata?.comments?.let { stripHtml(it) }

        val links = mutableListOf<FileUrl>()
        val formats = metadata?.formats.orEmpty().map { it.lowercase() }
        val preferred = listOf("epub", "mobi", "azw3", "pdf")
        val sortedFormats = formats.sortedBy { fmt ->
            val idx = preferred.indexOf(fmt)
            if (idx >= 0) idx else 99
        }

        for (fmt in sortedFormats) {
            val downloadUrl = "$baseUrl/get/$fmt/$id"
            links.add(FileUrl(downloadUrl, headers))
        }

        if (links.isEmpty()) {
            links.add(FileUrl("$baseUrl/get/epub/$id", headers))
        }

        return Book(
            name = title,
            img = FileUrl(coverUrl, headers),
            description = desc,
            links = links
        )
    }

    private fun stripHtml(html: String): String {
        return Jsoup.parseBodyFragment(html).text()
    }

    @Serializable
    private class SearchResponse(
        @SerialName("book_ids") val bookIds: List<Long> = emptyList(),
        @SerialName("total_num") val totalNum: Int = 0,
    )

    @Serializable
    private class BookMetadata(
        val title: String = "",
        val authors: List<String> = emptyList(),
        val comments: String? = null,
        val tags: List<String> = emptyList(),
        val formats: List<String> = emptyList(),
    )

    companion object {
        const val PREF_BASE_URL = "calibre_server_url"
        const val PREF_USER = "calibre_username"
        const val PREF_PASS = "calibre_password"
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    }
}
