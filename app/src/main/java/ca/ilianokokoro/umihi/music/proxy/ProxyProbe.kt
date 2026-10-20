package ca.ilianokokoro.umihi.music.proxy

import android.os.SystemClock
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * What one exit node actually does with the two hosts the app talks to.
 *
 * The pair is not interchangeable, which is the whole reason this exists. `music.youtube.com`
 * answers a search with an empty shelf on exits YouTube Music considers unsupported — a node
 * can serve plain YouTube perfectly and still hand back nothing playable from the catalog,
 * leaving the app on video audio with a channel name in the artist field. Measuring the two
 * apart is the only way to tell those nodes apart before playing anything.
 */
data class ProbeResult(
    /** Playable tracks the music catalog returned. Zero means the catalog is closed. */
    val musicTracks: Int = 0,
    val musicMs: Long = 0,
    /** Playable tracks plain YouTube search returned. */
    val webTracks: Int = 0,
    val webMs: Long = 0,
) {
    val musicOk: Boolean get() = musicTracks > 0
    val webOk: Boolean get() = webTracks > 0

    /** Nothing answered at all: the node is unusable. */
    val dead: Boolean get() = !musicOk && !webOk

    /**
     * One-line verdict for a list row.
     *
     * A closed catalog is spelled out rather than left blank, because it is the case worth
     * seeing: those are exactly the nodes that search fine and then play the video's audio.
     */
    val summary: String
        get() = "曲库 " + (if (musicOk) "✓ ${musicMs.seconds()}" else "✗") +
            " · YouTube " + (if (webOk) "✓ ${webMs.seconds()}" else "✗")

    private fun Long.seconds(): String =
        String.format(Locale.US, "%.1fs", this / 1000.0)
}

object ProxyProbe {

    /** Chinese pop, because a closed catalog hurts that search the most. */
    private const val QUERY = "周杰伦"

    /**
     * Measures both hosts through whatever tunnel is up right now.
     *
     * Deliberately runs the real search calls instead of pinging `generate_204`. A completed
     * handshake says nothing about whether the catalog answers, and whether the catalog answers
     * is the only question that matters here.
     *
     * The two run concurrently; they hit different hosts, so there is no shared connection to
     * serialise on, and the slower one simply reports a larger number.
     */
    suspend fun measure(): ProbeResult = coroutineScope {
        val music = async(Dispatchers.IO) {
            probe(
                call = { YoutubeApiClient.search(QUERY) },
                count = { body -> YoutubeDataExtractor.extractSearchResults(body).size },
            )
        }
        val web = async(Dispatchers.IO) {
            probe(
                call = { YoutubeApiClient.searchWeb(QUERY) },
                count = { body -> YoutubeDataExtractor.extractWebSearchResults(body).size },
            )
        }

        val musicResult = music.await()
        val webResult = web.await()

        ProbeResult(
            musicTracks = musicResult.first,
            musicMs = musicResult.second,
            webTracks = webResult.first,
            webMs = webResult.second,
        )
    }

    /** Track count and wall-clock cost of one search, with a failure flattened to zero tracks. */
    private suspend fun probe(
        call: suspend () -> String,
        count: (String) -> Int,
    ): Pair<Int, Long> {
        val started = SystemClock.elapsedRealtime()
        val tracks = runCatching { count(call()) }.getOrDefault(0)
        return tracks to (SystemClock.elapsedRealtime() - started)
    }
}
