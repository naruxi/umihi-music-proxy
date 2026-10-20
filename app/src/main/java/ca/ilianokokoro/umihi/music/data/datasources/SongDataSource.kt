package ca.ilianokokoro.umihi.music.data.datasources

import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.models.Song

class SongDataSource {
    suspend fun getSongInfo(songId: String): Song {
        return YoutubeDataExtractor.extractSongInfo(
            YoutubeApiClient.getPlayerInfo(
                songId,
                //  fields = Constants.YoutubeApi.PlayerInfo.Fields.SONG_INFO
            )
        )
    }

    suspend fun searchAutoComplete(query: String): List<String> {
        return YoutubeDataExtractor.extractSearchAutocompleteResults(
            YoutubeApiClient.searchAutoComplete(
                query
            )
        )
    }

    /**
     * Searches the music catalog, then falls back to all of YouTube when that comes back thin.
     *
     * The catalog endpoint drops its shelves — songs, albums, videos — for exits it considers
     * unsupported, answering with profiles, community playlists and podcasts instead. None of
     * those carry a video id, so the music parser legitimately yields nothing and the screen
     * said "No results" on a perfectly working connection. Plain YouTube search is unaffected by
     * that gating and returns videos whose audio the player already knows how to resolve.
     *
     * The verdict is remembered for the life of the process: on such an exit every query answers
     * the same way, and paying two round trips per search is exactly the kind of wait this app
     * is trying to avoid.
     */
    suspend fun search(query: String): List<Song> {
        var musicError: Throwable? = null

        if (!catalogUnavailable) {
            val musicAttempt = runCatching {
                YoutubeDataExtractor.extractSearchResults(YoutubeApiClient.search(query))
            }
            val musicResults = musicAttempt.getOrElse { error ->
                musicError = error
                emptyList()
            }

            if (musicResults.size >= MIN_MUSIC_RESULTS) return musicResults

            // Only latch when the request itself succeeded — a network failure says something
            // about the exit, not about the catalog.
            if (musicAttempt.isSuccess) catalogUnavailable = true
        }

        return runCatching {
            YoutubeDataExtractor.extractWebSearchResults(YoutubeApiClient.searchWeb(query))
        }.getOrElse { webError -> throw (musicError ?: webError) }
    }

    private companion object {
        const val MIN_MUSIC_RESULTS = 3

        @Volatile
        var catalogUnavailable = false
    }
}
