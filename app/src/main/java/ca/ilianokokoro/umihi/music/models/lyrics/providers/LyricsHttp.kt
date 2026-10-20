package ca.ilianokokoro.umihi.music.models.lyrics.providers

import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit
import android.util.Base64

/**
 * Transport shared by the domestic lyrics services.
 *
 * These are Chinese hosts reached from a Chinese network, so the tunnel is deliberately bypassed:
 * routing them through a foreign exit only adds a round trip at best, and lrclib-style timeouts at
 * worst — several of them answer a non-mainland address with an empty catalogue.
 *
 * The timeouts are tight on purpose. A lookup runs every provider at once and keeps whichever
 * answers first, so a source that hangs must give up quickly instead of holding a thread until the
 * whole lookup's deadline expires.
 */
internal object LyricsHttp {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val client = UmihiHttpClient.directClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun get(url: HttpUrl, referer: String): String? =
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }

    /**
     * Both QQ Music and Kugou hand back their lyrics base64-encoded.
     */
    fun decodeBase64(encoded: String): String? =
        try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
}
