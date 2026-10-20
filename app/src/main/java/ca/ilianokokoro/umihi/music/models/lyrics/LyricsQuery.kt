package ca.ilianokokoro.umihi.music.models.lyrics

import ca.ilianokokoro.umihi.music.models.Song

/**
 * The credit block YouTube attaches to auto-generated uploads: `Song · Artist` followed by the
 * album. It stays accurate even when the title and byline that search handed us did not.
 */
data class TrackCredits(
    val title: String,
    val artist: String,
    val album: String = "",
)

/**
 * Everything a lyrics provider is allowed to know about a song.
 *
 * Songs that came out of plain YouTube search carry a channel name where the artist belongs and
 * a title that still wears its marketing tail, which is enough to make a strict lookup miss. So
 * [candidatesFrom] also builds scrubbed variants and hands the provider several forms to try, in
 * descending order of confidence.
 */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String = "",
) {
    companion object {
        private const val MAX_CANDIDATES = 3
        private const val MAX_ARTIST_PART_LENGTH = 40

        private val BRACKETS = Regex("""[(\[（【]([^)\]）】]*)[)\]）】]""")
        private val TRAILING_TAIL = Regex(
            """\s*[-–—|]?\s*(?:official\s+)?""" +
                """(?:music\s+video|lyric(?:s)?\s+video|lyrics|video|audio|visualizer|mv|m/v|""" +
                """remaster(?:ed)?|4k|8k|hd|hq)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val DASH_SPLIT = Regex("""\s+[-–—]\s+""")
        private val TOPIC_SUFFIX = Regex("""\s*[-–—]\s*topic\s*$""", RegexOption.IGNORE_CASE)
        private val VEVO_SUFFIX = Regex("""(?:\s*[-–—]\s*)?vevo\s*$""", RegexOption.IGNORE_CASE)
        private val OFFICIAL_WORD = Regex("""\s*\bofficial\b\s*""", RegexOption.IGNORE_CASE)
        private val CHINESE_OFFICIAL = Regex(
            """(?:官方频道|官方帳號|官方账号|官方音乐|官方音樂|官方音频|官方音頻|官方mv|官方版|完整版|高清|歌詞版|歌词版)""",
            RegexOption.IGNORE_CASE,
        )
        private val WHITESPACE = Regex("""\s+""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]""")

        /**
         * Words that only ever show up in a title because the upload is a video, not because the
         * song is called that. `live` and `remix` are deliberately absent: those versions really
         * are different recordings, and matching the studio lyrics to them would be wrong.
         */
        private val NOISE_WORDS = listOf(
            "official", "music video", "lyric video", "lyrics", "lyric", "audio",
            "visualizer", "remaster", "remastered", "mv", "m/v", "4k", "8k", "hd", "hq",
            "官方", "歌词", "歌詞", "完整版", "高清", "中字", "字幕",
        )

        fun fromSong(song: Song): LyricsQuery =
            LyricsQuery(
                title = song.title,
                artist = song.artists,
                durationMs = (song.durationSeconds ?: 0) * 1_000L,
            )

        /**
         * Lookup forms for [song], best first.
         *
         * A title of the shape `Artist - Song` is the most valuable variant, since that is how
         * plain YouTube search words its results; the scrubbed form is next, and the untouched
         * values are kept as the last resort so a clean music-catalog result is queried exactly
         * as before.
         */
        fun candidatesFrom(song: Song): List<LyricsQuery> {
            val original = fromSong(song)
            if (original.title.isBlank()) return emptyList()

            val variants = LinkedHashSet<LyricsQuery>()

            dashSplit(original)?.let { variants += it }
            variants += LyricsQuery(
                title = scrubTitle(original.title),
                artist = scrubArtist(original.artist),
                durationMs = original.durationMs,
            )
            variants += original

            return variants
                .filter { it.title.isNotBlank() }
                .take(MAX_CANDIDATES)
        }

        fun scrubTitle(raw: String): String {
            val withoutNoiseBrackets = BRACKETS.replace(raw) { match ->
                if (isNoise(match.groupValues[1])) " " else match.value
            }
            return withoutNoiseBrackets
                .replace(TRAILING_TAIL, " ")
                .replace(WHITESPACE, " ")
                .trim(' ', '-', '–', '—', '|', ',', '.', '·')
                .ifBlank { raw.trim() }
        }

        fun scrubArtist(raw: String): String = raw
            .replace(TOPIC_SUFFIX, "")
            .replace(VEVO_SUFFIX, "")
            .replace(CHINESE_OFFICIAL, " ")
            .replace(OFFICIAL_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim(' ', '-', '–', '—', '|', ',', '·')
            .ifBlank { raw.trim() }

        private fun isNoise(bracket: String): Boolean {
            val lower = bracket.lowercase()
            return NOISE_WORDS.any { lower.contains(it) }
        }

        /**
         * Splits `Artist - Song` when both halves are plausible; null when they are not.
         *
         * The tail is scrubbed before splitting so `Love - Remastered` stays one title, and the
         * halves are swapped when the right one is clearly the artist we already knew about,
         * which is how `Song - Artist` uploads are written.
         */
        private fun dashSplit(query: LyricsQuery): LyricsQuery? {
            val parts = DASH_SPLIT.split(scrubTitle(query.title))
            if (parts.size != 2) return null

            val first = parts[0].trim()
            val second = parts[1].trim()
            if (first.isBlank() || second.isBlank()) return null
            if (first.length > MAX_ARTIST_PART_LENGTH) return null

            val knownArtist = comparable(scrubArtist(query.artist))
            val secondIsArtist = knownArtist.isNotEmpty() &&
                comparable(second).let { it.isNotEmpty() && it.contains(knownArtist) }

            return if (secondIsArtist) {
                LyricsQuery(scrubTitle(first), scrubArtist(second), query.durationMs)
            } else {
                LyricsQuery(scrubTitle(second), scrubArtist(first), query.durationMs)
            }
        }

        private fun comparable(value: String): String = NON_WORD.replace(value.lowercase(), "")
    }
}
