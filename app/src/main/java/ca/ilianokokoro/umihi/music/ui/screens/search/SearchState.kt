package ca.ilianokokoro.umihi.music.ui.screens.search

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.models.Song


data class SearchState(
    val search: String = String(),
    val suggestions: List<String> = listOf(),
    val screenState: ScreenState = ScreenState.Success(),
    val isLoggedIn: Boolean = false,
    val filter: SearchFilter = SearchFilter.SONGS
)


/**
 * Which vertical of the search result page to ask for.
 *
 * Both verticals answer with the same renderer shape, so switching only swaps the request
 * `params`; playback always resolves the audio-only stream, which is why [VIDEOS] is worth
 * having — live takes, uploads and covers that YouTube Music does not list as a catalog song
 * are only reachable there.
 */
enum class SearchFilter(val params: String) {
    SONGS(Constants.YoutubeApi.Search.FILTER),
    VIDEOS(Constants.YoutubeApi.Search.FILTER_VIDEOS);

    fun next(): SearchFilter = if (this == SONGS) VIDEOS else SONGS
}


sealed class ScreenState {
    data class Success(
        val results: List<Song> = listOf()
    ) : ScreenState()

    data object Loading : ScreenState()
    data class Error(val exception: Exception) : ScreenState()
}
