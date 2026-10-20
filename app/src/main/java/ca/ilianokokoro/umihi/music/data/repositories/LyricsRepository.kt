package ca.ilianokokoro.umihi.music.data.repositories

import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.models.lyrics.Lyrics
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsQuery
import ca.ilianokokoro.umihi.music.models.lyrics.TrackCredits
import ca.ilianokokoro.umihi.music.models.lyrics.providers.BetterLyricsProvider
import ca.ilianokokoro.umihi.music.models.lyrics.providers.LrcLibProvider

class LyricsRepository {
    /**
     * Lyrics for [song], or null when nothing could be matched.
     *
     * The order reflects how much we trust each source of information: the scrubbed variants of
     * the title and artist first, then the untouched values, then the credit block YouTube puts
     * inside auto-generated videos. Each step only runs when the one before it found nothing, so
     * a clean music-catalog song still costs a single lookup.
     */
    suspend fun getLyrics(song: Song): Lyrics? {
        val candidates = LyricsQuery.candidatesFrom(song).ifEmpty { return null }

        for (query in candidates) {
            LRC_LIB.getLyrics(query)?.let { return it }
        }

        BETTER_LYRICS.getLyrics(candidates.first())?.let { return it }

        val credits = creditsFor(song) ?: return null
        val creditQuery = LyricsQuery(
            title = credits.title,
            artist = credits.artist,
            durationMs = (song.durationSeconds ?: 0) * MS_PER_SECOND,
            album = credits.album,
        )

        LRC_LIB.getLyrics(creditQuery)?.let { return it }
        return BETTER_LYRICS.getLyrics(creditQuery)
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

        private val LRC_LIB = LrcLibProvider()
        private val BETTER_LYRICS = BetterLyricsProvider()

        /** Access-ordered so the eldest entry is the least recently used one. */
        private val creditsCache =
            object : LinkedHashMap<String, List<TrackCredits>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<TrackCredits>>
                ): Boolean = size > MAX_CACHED_CREDITS
            }
    }
}
