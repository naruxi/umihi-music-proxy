package ca.ilianokokoro.umihi.music.proxy

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Puts the login [android.webkit.WebView] inside the tunnel.
 *
 * WebView runs on the Chromium network stack and never consults OkHttp's `ProxySelector`, so the
 * auth screen would otherwise go out direct and hang forever behind the firewall while every other
 * request in the app worked fine through the tunnel.
 *
 * `ProxyController`'s override is process-wide — one call covers every WebView in the app — which
 * is exactly the scope needed here.
 */
object ProxyWebView {

    private const val TAG = "UmihiProxy"
    private const val LOOPBACK = "127.0.0.1"

    /** Port currently pushed into the WebView stack, or null when WebView goes direct. */
    @Volatile
    private var appliedPort: Int? = null

    private val main = Handler(Looper.getMainLooper())
    private val directExecutor = Executor { it.run() }

    /**
     * Routes WebView traffic through the tunnel. Idempotent, so it is safe to call both when the
     * tunnel comes up and again right before a WebView is created.
     */
    fun apply(port: Int) {
        if (appliedPort == port) return
        if (!supported()) {
            Log.w(TAG, "webview: proxy override unsupported by this WebView provider")
            return
        }
        appliedPort = port

        val config = ProxyConfig.Builder()
            .addProxyRule("$LOOPBACK:$port")
            .build()

        runOnMain {
            runCatching {
                ProxyController.getInstance().setProxyOverride(
                    config,
                    { Log.i(TAG, "webview: proxy override -> $LOOPBACK:$port") },
                    directExecutor
                )
            }.onFailure { Log.w(TAG, "webview: apply failed: ${it.message}") }
        }
    }

    /** Drops the override so WebView traffic goes out direct again. */
    fun clear() {
        if (appliedPort == null) return
        appliedPort = null
        if (!supported()) return

        runOnMain {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(
                    { Log.i(TAG, "webview: proxy override cleared") },
                    directExecutor
                )
            }.onFailure { Log.w(TAG, "webview: clear failed: ${it.message}") }
        }
    }

    /**
     * Re-applies the override for the port that is live right now, if any.
     *
     * Called from the auth screen: a WebView created before the tunnel came up keeps the direct
     * route until the override is applied.
     */
    fun syncWithRuntime() {
        val port = ProxyRuntime.port
        if (port != null && ProxyRuntime.enabled) apply(port) else clear()
    }

    private fun supported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }.getOrDefault(false)

    /**
     * Runs inline when already on the main thread: the auth screen applies the override and then
     * constructs the WebView in the same pass, so posting would let the first load escape direct.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
