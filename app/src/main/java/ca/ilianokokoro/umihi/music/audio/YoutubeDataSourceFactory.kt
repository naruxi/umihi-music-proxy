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
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    private companion object {
        /** Comfortably inside the multi-hour lifetime of a googlevideo URL. */
        const val TRUSTED_URL_MS = 5 * 60 * 1000L

        /** A handful of tracks ahead is all the player ever needs. */
        const val MAX_ENTRIES = 8
    }
}
