package ca.ilianokokoro.umihi.music.proxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Process-wide view of the running tunnel, read by the HTTP stack on every request.
 *
 * Kept as plain volatile state (instead of rebuilding OkHttp clients) so toggling the proxy
 * or switching nodes takes effect immediately without invalidating the client, its connection
 * pool, or any in-flight ExoPlayer source.
 */
object ProxyRuntime {

    /** Loopback port of the mixed inbound, or null when the tunnel is down. */
    @Volatile
    var port: Int? = null

    /**
     * User intent. Distinct from [port] because the core takes a moment to come up after a
     * cold start; requests arriving in that window should wait rather than go out direct and
     * look like a broken network.
     */
    @Volatile
    var enabled: Boolean = false

    /** Last failure worth surfacing in Settings, cleared on a successful start. */
    @Volatile
    var lastError: String? = null

    val isActive: Boolean get() = port != null

    private const val LOOPBACK = "127.0.0.1"

    fun loopback(): InetSocketAddress? = port?.let { InetSocketAddress(LOOPBACK, it) }

    /**
     * Waits briefly for the tunnel to come up.
     *
     * A cold app start races the user's first tap (and PlaybackService's own boot), so requests
     * that arrive while the engine is still initialising would otherwise fail. Blocking here is
     * acceptable: callers are on OkHttp's IO threads, and the wait only happens while the core
     * is genuinely still starting.
     */
    fun awaitLoopback(timeoutMillis: Long = 6_000): InetSocketAddress? {
        loopback()?.let { return it }
        if (!enabled) return null

        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            loopback()?.let { return it }
            // Enabled was turned off while waiting — stop immediately.
            if (!enabled) return null
            Thread.sleep(50)
        }
        return loopback()
    }
}

/**
 * Routes every request through the local tunnel when it is up. Wired into OkHttp via
 * `proxySelector(...)`, so it is consulted per request instead of being baked in.
 */
object ProxyRouteSelector : ProxySelector() {

    private val noProxy = listOf(Proxy.NO_PROXY)

    override fun select(uri: URI): List<Proxy> {
        if (!ProxyRuntime.enabled) return noProxy

        // The tunnel's own inbound must never be routed back into itself.
        val host = uri.host
        if (host != null && (host == "127.0.0.1" || host == "localhost" || host == "::1")) {
            return noProxy
        }

        val address = ProxyRuntime.awaitLoopback() ?: return noProxy
        return listOf(Proxy(Proxy.Type.HTTP, address))
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {
        // Nothing to do: the next request re-reads ProxyRuntime and retries.
    }
}
