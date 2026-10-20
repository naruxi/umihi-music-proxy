package ca.ilianokokoro.umihi.music.audio

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class YoutubeDataSourceFactory(
    private val application: Application,
    private val cacheDataSourceFactory: DataSource.Factory
) : DataSource.Factory {

    /**
     * ExoPlayer re-opens its [DataSource] far more often than one might expect: when buffering
     * starts, on every seek outside the buffered region, and on each retry after a network
     * hiccup. Each open used to walk the whole resolution path again, and
     * [YoutubeDataExtractor.getSongPlayerUrl] validates the stored stream URL with a HEAD
     * request — one extra round trip per open, plus a full player-endpoint call whenever that
     * HEAD fails on a flaky link. Over a proxied connection that extra latency is audible as
     * a stutter, and it also negates the URL cache whenever the tunnel merely blips.
     *
     * Googlevideo URLs stay valid for hours, so trusting one for a few minutes is safe and
     * removes the round trip entirely within a single track.
     */
    private val resolvedUris = LruCache<String, Entry>(MAX_ENTRIES)

    private class Entry(val uri: Uri, val atElapsedRealtime: Long)

    @OptIn(UnstableApi::class)
    override fun createDataSource(): DataSource {
        return ResolvingDataSource(cacheDataSourceFactory.createDataSource()) { dataSpec ->
            dataSpec.withUri(resolveUri(dataSpec.uri))
        }
    }

    private fun resolveUri(uri: Uri): Uri {
        if (!uri.toString()
                .startsWith(Constants.YoutubeApi.YOUTUBE_URL_PREFIX) && uri.scheme != null
        ) {
            return uri
        }

        val key = uri.toString()
        val now = SystemClock.elapsedRealtime()

        synchronized(resolvedUris) {
            resolvedUris.get(key)?.let { entry ->
                if (now - entry.atElapsedRealtime < TRUSTED_URL_MS) return entry.uri
                resolvedUris.remove(key)
            }
        }

        val streamUri = runBlocking(Dispatchers.IO) {
            YoutubeDataExtractor.getSongPlayerUrl(
                context = application,
                song = Song.createFromYoutubeUrl(key),
                allowLocal = true
            )
        }

        val result = if (streamUri.startsWith("/")) {
            Uri.fromFile(File(streamUri))
        } else {
            streamUri.toUri()
        }

        synchronized(resolvedUris) { resolvedUris.put(key, Entry(result, now)) }
        return result
    }

    /**
     * Resolves [songUri] and opens the socket to its stream host, before the player needs either.
     *
     * Called for the next track while the current one plays, this moves the two expensive parts
     * of a track change off the transition: the player-endpoint call (which may also include a
     * bot-check retry) and, more importantly, the TLS handshake with the googlevideo host that
     * URL points at.
     *
     * That handshake is the real cost. Every track streams from a different `rr*.googlevideo.com`
     * node, so it is never in the connection pool, and measured through a relayed proxy it takes
     * anywhere from 0.3s to 3.6s — against the 0.1s a pooled socket costs. Once this runs, the
     * player's first request reuses the socket instead of paying for it again.
     */
    suspend fun prefetch(songUri: String) {
        if (!songUri.startsWith(Constants.YoutubeApi.YOUTUBE_URL_PREFIX)) return

        val now = SystemClock.elapsedRealtime()
        val cached = synchronized(resolvedUris) {
            resolvedUris.get(songUri)?.takeIf { now - it.atElapsedRealtime < TRUSTED_URL_MS }?.uri
        }

        val streamUri = cached ?: withContext(Dispatchers.IO) {
            runCatching { resolveUri(songUri.toUri()) }.getOrNull()
        } ?: return

        // Downloaded tracks resolve to a file, and a local file has no host to warm.
        if (streamUri.scheme != "https") return

        warmConnection(streamUri)
    }

    /**
     * One small ranged request, drained in full.
     *
     * The body matters less than the handshake that precedes it, but it does have to be read to
     * the end: OkHttp only returns a socket to the pool once its response is complete, and a
     * half-read body would be discarded — leaving the player to handshake all over again.
     */
    private fun warmConnection(streamUri: Uri) {
        runCatching {
            val request = Request.Builder()
                .url(streamUri.toString())
                .header("Range", "bytes=0-1023")
                .build()

            UmihiHttpClient.downloadClient.newCall(request).execute().use { response ->
                val stream = response.body?.byteStream() ?: return@use
                val sink = ByteArray(WARM_READ_BYTES)
                var total = 0
                while (total < WARM_READ_BYTES) {
                    val read = stream.read(sink)
                    if (read < 0) break
                    total += read
                }
            }
        }
    }

    private companion object {
        /** Comfortably inside the multi-hour lifetime of a googlevideo URL. */
        const val TRUSTED_URL_MS = 5 * 60 * 1000L

        /** A handful of tracks ahead is all the player ever needs. */
        const val MAX_ENTRIES = 8

        /** One kilobyte of audio: enough to complete the response and release the socket. */
        const val WARM_READ_BYTES = 1_024
    }
}
