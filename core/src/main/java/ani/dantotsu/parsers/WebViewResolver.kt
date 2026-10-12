package ani.dantotsu.parsers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private class BypassWebViewClient(
    private val originalUrl: String,
    private val cookieManager: CookieManager,
    private val webView: WebView,
    private val latch: CountDownLatch,
    private val solved: AtomicBoolean
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
        super.onPageFinished(view, finishedUrl)
        val targetUrl = finishedUrl ?: originalUrl
        val cookies = cookieManager.getCookie(targetUrl) ?: ""
        val hasDdg = cookies.contains("__ddg1_") || cookies.contains("__ddg2_")
        val hasCf = cookies.contains("cf_clearance")
        val isBypassed = !targetUrl.contains("ddos-guard") && !targetUrl.contains("check=1") &&
                (hasDdg || hasCf || (!targetUrl.contains(".well-known") && cookies.isNotEmpty()))

        if (isBypassed && solved.compareAndSet(false, true)) {
            cookieManager.flush()
            latch.countDown()
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Throwable) {}
        }
    }
}

private class BypassRunnable(
    private val url: String,
    private val userAgent: String,
    private val latch: CountDownLatch,
    private val solved: AtomicBoolean
) : Runnable {
    @SuppressLint("SetJavaScriptEnabled")
    override fun run() {
        try {
            val context = WebViewResolver.getApplicationContext()
            if (context == null) {
                latch.countDown()
                return
            }

            val webView = WebView(context)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.userAgentString = userAgent
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false

            webView.webViewClient = BypassWebViewClient(url, cookieManager, webView, latch, solved)
            webView.loadUrl(url)
        } catch (_: Throwable) {
            latch.countDown()
        }
    }
}

object WebViewResolver {

    private const val DEFAULT_TIMEOUT_SECONDS = 30L

    fun getCookies(url: String): String {
        return try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    internal fun getApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            currentApplicationMethod.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    fun resolve(url: String, userAgent: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): String {
        val latch = CountDownLatch(1)
        val solved = AtomicBoolean(false)

        try {
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post(BypassRunnable(url, userAgent, latch, solved))
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Throwable) {}

        return getCookies(url)
    }

    suspend fun getWithBypass(
        url: String,
        headers: Map<String, String>,
        client: Requests,
        userAgent: String
    ): NiceResponse {
        val reqHeaders = headers.toMutableMap()
        val existingCookies = getCookies(url)
        if (existingCookies.isNotBlank() && !reqHeaders.containsKey("Cookie")) {
            reqHeaders["Cookie"] = existingCookies
        }

        val response = try {
            client.get(url, headers = reqHeaders)
        } catch (e: Exception) {
            val msg = e.message.orEmpty().lowercase()
            if (msg.contains("403") || msg.contains("ddos-guard") || msg.contains("cloudflare")) {
                val resolvedCookies = resolve(url, userAgent)
                if (resolvedCookies.isNotBlank()) {
                    reqHeaders["Cookie"] = resolvedCookies
                    return client.get(url, headers = reqHeaders)
                }
            }
            throw e
        }

        if (response.code in listOf(403, 503)) {
            val server = response.headers["Server"]?.lowercase() ?: ""
            val body = runCatching { response.text }.getOrDefault("").lowercase()
            if (server.contains("ddos-guard") || server.contains("cloudflare") ||
                body.contains("ddos-guard") || body.contains("turnstile") || body.contains("challenge")) {
                val resolvedCookies = resolve(url, userAgent)
                if (resolvedCookies.isNotBlank()) {
                    reqHeaders["Cookie"] = resolvedCookies
                    return client.get(url, headers = reqHeaders)
                }
            }
        }

        return response
    }
}
