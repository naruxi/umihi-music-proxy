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
     * The catalog occasionally answers with an empty shelf, which produces no playable ids at
     * all. Plain YouTube search is unaffected and returns videos whose audio the player already
     * knows how to resolve, so it is kept as a fallback.
     *
     * The verdict is deliberately *not* remembered: whether the catalog answers depends on the
     * exit node in use, and a node that failed once can start working a minute later. Latching
     * that first failure would pin every later search to the fallback — and to the channel names
     * it puts in the artist field, which is what makes lyrics unmatchable.
     */
    suspend fun search(query: String): List<Song> {
        var musicError: Throwable? = null

        val musicAttempt = runCatching {
            YoutubeDataExtractor.extractSearchResults(YoutubeApiClient.search(query))
        }
        val musicResults = musicAttempt.getOrElse { error ->
            musicError = error
            emptyList()
        }

        if (musicResults.size >= MIN_MUSIC_RESULTS) return musicResults

        return runCatching {
            YoutubeDataExtractor.extractWebSearchResults(YoutubeApiClient.searchWeb(query))
        }.getOrElse { webError -> throw (musicError ?: webError) }
    }

    private companion object {
        const val MIN_MUSIC_RESULTS = 3
    }
}
