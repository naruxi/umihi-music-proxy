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
    /**
     * The keyword to hand a domestic search endpoint.
     *
     * The artist half is dropped when it does not look like an artist. Video-derived songs put
     * a channel name in that field, and those are regularly built out of the exact words that
     * make a title noisy — `周傑倫歌詞`, `GM Lyric`, `Peculiar音樂`. Appending one of those turns a
     * search that would have matched into an empty result, which is worse than a looser query
     * because the scoring would still have picked the right hit.
     */
    val keyword: String
        get() {
            val trimmed = artist.trim()
            val usable = trimmed.isNotEmpty() &&
                trimmed.length <= MAX_ARTIST_IN_KEYWORD &&
                !SUSPECT_ARTIST.containsMatchIn(trimmed)
            return if (usable) "$title $trimmed" else title.trim()
        }

    companion object {
        private const val MAX_CANDIDATES = 3
        private const val MAX_ARTIST_PART_LENGTH = 40

        /** Longer than this and the artist is a paragraph, not a name. */
        private const val MAX_ARTIST_IN_KEYWORD = 18

        /** Words that mean the "artist" field is really a channel, a lyric page or a label. */
        private val SUSPECT_ARTIST = Regex(
            """歌词|歌詞|lyric|music|音樂|音乐|頻道|频道|channel|topic|vevo|official|""" +
                """精選|精选|合集|字幕|sub""",
            RegexOption.IGNORE_CASE,
        )

        private val BRACKETS = Regex("""[(\[（【]([^)\]）】]*)[)\]）】]""")
        private val TRAILING_TAIL = Regex(
            """\s*[-–—|]?\s*(?:official\s+)?""" +
                """(?:music\s+video|lyric(?:s)?\s+video|lyrics|video|audio|visualizer|mv|m/v|""" +
                """remaster(?:ed)?|flac|wav|lossless|4k|8k|hd|hq)\s*$""",
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
            "高音質", "高音质", "無損", "无损", "純享", "纯享", "動態歌詞", "动态歌词",
            "修復版", "修复版", "無損音樂", "无损音乐",
        )

        /**
         * Chinese noise words, stripped wherever they appear.
         *
         * Video titles stack these anywhere: `周杰倫 晴天 無損音樂FLAC 歌詞LYRICS 純享` carries
         * three of them between the artist and the end, so cutting only the tail left the rest
         * in the search keyword. None can be part of a real song title, so position is
         * irrelevant. `live` is deliberately absent — a live recording is a different version.
         */
        private val CHINESE_NOISE = Regex(
            """(?:[-–—|·]\s*)?(?:""" +
                """官方(?:mv|版|頻道|频道|帳號|账号|音頻|音频|音樂|音乐|高清|完整)?|""" +
                """完整版|高清|高音質|高音质|無損(?:音樂)?|无损(?:音乐)?|純享|纯享|""" +
                """動態歌詞|动态歌词|歌詞版|歌词版|歌詞|歌词|中文字幕|字幕|""" +
                """修復版|修复版|超清|超高清|畫質|画质""" +
                """)\s*""",
            RegexOption.IGNORE_CASE,
        )

        /** `《歌名》` and `【歌名】`: Chinese uploads mark the song itself this way. */
        private val MARKED_TITLE = Regex("""[《【]([^》】]{1,30})[》】]""")

        /** The marks themselves, as opposed to what they wrap. */
        private val MARK_BRACKETS = Regex("""[《》【】]""")

        /** A marked span longer than this is a sentence, not a song name. */
        private const val MAX_MARKED_TITLE = 20

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
            // Whatever sits inside 《》 / 【】 is usually the song and nothing else. It goes
            // ahead of the scrubbed variant because the domestic sources only ask about two
            // spellings, and a long title still carrying its channel name wastes one of them.
            markedTitle(original.title)?.let {
                variants += LyricsQuery(
                    title = it,
                    artist = scrubArtist(original.artist),
                    durationMs = original.durationMs,
                )
            }
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

        /** The song name a video title wraps in 《》 or 【】, when it wraps one at all. */
        private fun markedTitle(raw: String): String? =
            MARKED_TITLE.findAll(raw)
                .map { match -> match.groupValues[1].trim() }
                .firstOrNull { candidate ->
                    candidate.isNotBlank() &&
                        candidate.length <= MAX_MARKED_TITLE &&
                        !isNoise(candidate)
                }
                ?.let { cleanTail(it) }
                ?.takeIf { it.isNotBlank() }

        fun scrubTitle(raw: String): String = cleanTail(raw).ifBlank { raw.trim() }

        private fun cleanTail(raw: String): String {
            val withoutNoiseBrackets = BRACKETS.replace(raw) { match ->
                if (isNoise(match.groupValues[1])) " " else match.value
            }
            var value = withoutNoiseBrackets.replace(TRAILING_TAIL, " ")

            // Noise words stack (`高清 完整版 官方MV`), and they sit anywhere, so repeat until
            // the string stops changing. The tail pattern has to be in the loop as well:
            // removing `純享` from `歌詞LYRICS 純享` uncovers `LYRICS`, which only the tail rule
            // recognises.
            while (true) {
                val stripped = value
                    .replace(CHINESE_NOISE, " ")
                    .replace(TRAILING_TAIL, " ")
                if (stripped == value) break
                value = stripped
            }

            // The bracket characters themselves are never part of a title, but the span they
            // wrap often is, so they are turned into spaces rather than trimmed off the ends —
            // trimming would eat the closing bracket of a span that was kept.
            return value
                .replace(MARK_BRACKETS, " ")
                .replace(WHITESPACE, " ")
                .trim(' ', '-', '–', '—', '|', ',', '.', '·')
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
