package ca.ilianokokoro.umihi.music.data.repositories

import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.models.lyrics.Lyrics
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsQuery
import ca.ilianokokoro.umihi.music.models.lyrics.TrackCredits
import ca.ilianokokoro.umihi.music.models.lyrics.providers.LyricsProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class LyricsRepository {
    /**
     * Lyrics for [song], or null when nothing could be matched.
     *
     * Two passes. The first asks every provider at once with the scrubbed title and artist
     * variants; the second only runs when that found nothing and asks them again with the credit
     * block YouTube puts inside auto-generated videos, which stays accurate even when the search
     * result handed us a channel name and a title wearing its marketing tail.
     *
     * Racing the providers is what makes five sources affordable: the answer arrives as soon as
     * the fastest one has it, instead of after four misses in series. Over a relayed proxy the
     * spread between "fastest" and "slowest" is seconds, so serial lookups were what made lyrics
     * feel like they never loaded.
     */
    suspend fun getLyrics(song: Song): Lyrics? {
        val candidates = LyricsQuery.candidatesFrom(song).ifEmpty { return null }

        lookup(candidates)?.let { return it }

        val credits = creditsFor(song) ?: return null
        val creditQuery = LyricsQuery(
            title = credits.title,
            artist = credits.artist,
            durationMs = (song.durationSeconds ?: 0) * MS_PER_SECOND,
            album = credits.album,
        )

        return lookup(listOf(creditQuery))
    }

    /**
     * First hit across [LyricsProviders.all], or null once every provider has given up.
     *
     * The providers run on a detached supervisor job rather than on the caller's scope: their
     * HTTP calls are blocking, so a scope tied to the caller would keep the caller waiting for
     * the slowest source even after a hit had already arrived. Nothing outlives the lookup for
     * longer than one request — each call carries its own timeout.
     */
    private suspend fun lookup(queries: List<LyricsQuery>): Lyrics? {
        inFlight?.cancel()
        val supervisor = SupervisorJob()
        inFlight = supervisor

        val hits = Channel<Lyrics>(Channel.UNLIMITED)
        val scope = CoroutineScope(supervisor + Dispatchers.IO)

        val jobs = LyricsProviders.all.map { provider ->
            scope.launch {
                val found = try {
                    withTimeout(PROVIDER_TIMEOUT_MS) { provider.getLyrics(queries) }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    printe("${provider.name} lyrics lookup failed: ${t.message}")
                    null
                }
                if (found != null) {
                    hits.trySend(found)
                }
            }
        }

        scope.launch {
            jobs.joinAll()
            hits.close()
        }

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { hits.receiveCatching().getOrNull() }
        supervisor.cancel()
        return result
    }

    /**
     * The credit block behind a video, fetched once and remembered.
     *
     * An empty list means the video carries no auto-generated credits, which is just as permanent
     * an answer as a hit, so both are cached. A failure to reach the player endpoint is not
     * cached — the next song change gets to try again.
     */
    private suspend fun creditsFor(song: Song): TrackCredits? {
        val cached = synchronized(creditsCache) { creditsCache[song.youtubeId] }
        if (cached != null) return cached.firstOrNull()

        val fetched = runCatching {
            YoutubeDataExtractor.extractDescriptionCredits(
                YoutubeApiClient.getPlayerInfo(song.youtubeId, fields = PLAYER_FIELDS)
            )
        }.getOrNull() ?: return null

        synchronized(creditsCache) { creditsCache[song.youtubeId] = listOf(fetched) }
        return fetched
    }

    companion object {
        private const val MS_PER_SECOND = 1_000L
        private const val PLAYER_FIELDS = "videoDetails"
        private const val MAX_CACHED_CREDITS = 128

        /** How long one source may take before the lookup stops waiting on it. */
        private const val PROVIDER_TIMEOUT_MS = 9_000L

        /** Ceiling for the whole lookup, so a wall of timeouts still resolves. */
        private const val TOTAL_TIMEOUT_MS = 10_000L

        @Volatile
        private var inFlight: Job? = null

        /** Access-ordered so the eldest entry is the least recently used one. */
        private val creditsCache =
            object : LinkedHashMap<String, List<TrackCredits>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<TrackCredits>>
                ): Boolean = size > MAX_CACHED_CREDITS
            }
    }
}
