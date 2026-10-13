package ani.dantotsu.parsers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class SafeResponse(
    val code: Int,
    val isSuccessful: Boolean,
    val text: String,
    val url: String,
    val headers: Map<String, String> = emptyMap()
) {
    val document: Document by lazy {
        Jsoup.parse(text, url)
    }
}

object SafeHttp {

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(
        client: Any?,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): SafeResponse = withContext(Dispatchers.IO) {
        val okClient: OkHttpClient = try {
            if (client != null) {
                val method = client.javaClass.methods.firstOrNull { 
                    it.name == "getBaseClient" || it.name == "getOkHttpClient" 
                }
                (method?.invoke(client) as? OkHttpClient) ?: defaultClient
            } else {
                defaultClient
            }
        } catch (_: Throwable) {
            defaultClient
        }

        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            reqBuilder.addHeader(k, v)
        }

        val response = okClient.newCall(reqBuilder.build()).execute()
        val code = response.code
        val isSuccessful = response.isSuccessful
        val bodyText = response.body?.string().orEmpty()
        val finalUrl = response.request.url.toString()
        val respHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            respHeaders[response.headers.name(i)] = response.headers.value(i)
        }

        SafeResponse(code, isSuccessful, bodyText, finalUrl, respHeaders)
    }
}
