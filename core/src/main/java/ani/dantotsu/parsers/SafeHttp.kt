package ani.dantotsu.parsers

import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

object SafeHttp {

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val defaultParser: ResponseParser = object : ResponseParser {
        override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
            throw UnsupportedOperationException("JSON parsing not supported by fallback parser")

        override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = null

        override fun writeValueAsString(obj: Any): String = ""
    }

    suspend fun get(
        client: Requests?,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): NiceResponse = withContext(Dispatchers.IO) {
        val okClient: OkHttpClient = try {
            client?.baseClient ?: defaultClient
        } catch (_: Throwable) {
            defaultClient
        }

        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            reqBuilder.addHeader(k, v)
        }

        val response = okClient.newCall(reqBuilder.build()).execute()
        val parser: ResponseParser = try {
            client?.responseParser ?: defaultParser
        } catch (_: Throwable) {
            defaultParser
        }

        NiceResponse(response, parser)
    }
}
