package ca.ilianokokoro.umihi.music.core

import ca.ilianokokoro.umihi.music.proxy.ProxyRouteSelector
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

object UmihiHttpClient {
    /**
     * Every YouTube/InnerTube/NewPipe call goes through here, so a single proxy selector here
     * covers the whole data layer: search, browse, playlists, auth, play-URL resolution.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(ProxyRouteSelector)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Ignores the tunnel. Used to fetch proxy subscriptions, which must work before a tunnel
     * exists and are usually reachable directly.
     */
    val directClient: OkHttpClient by lazy {
        client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .build()
    }
}
