package ca.ilianokokoro.umihi.music.models.lyrics.providers

import ca.ilianokokoro.umihi.music.models.lyrics.LyricsProvider

/**
 * Every lyrics source the app knows about.
 *
 * Lookups ask all of them at once and keep whichever answers first, so this list is a set rather
 * than a priority order — see [ca.ilianokokoro.umihi.music.data.repositories.LyricsRepository].
 * Each source covers a different catalogue: lrclib is the only one with proper Western coverage,
 * QQ Music and NetEase hold the Chinese catalogues that the reachable YouTube Music catalog no
 * longer serves, and Kugou catches older releases the other two have dropped.
 */
object LyricsProviders {
    val all: List<LyricsProvider> by lazy {
        listOf(
            LrcLibProvider(),
            QqMusicProvider(),
            NetEaseProvider(),
            KugouProvider(),
            BetterLyricsProvider(),
        )
    }
}
