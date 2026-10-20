package ca.ilianokokoro.umihi.music.models.lyrics

import kotlin.math.abs

/**
 * Ranks a search hit against the song that is actually playing.
 *
 * The domestic services return a dozen covers for every query, and the first hit is regularly the
 * wrong one: a re-recorded version, a piano arrangement, or an entirely unrelated track that
 * happens to share the title. So a title has to overlap at all before artist and duration get a
 * say, and anything under [MIN_SCORE] is treated as "this service has nothing for us".
 *
 * The weights mirror the ones the lrclib search already used, which were tuned against real
 * mismatches: a title-only hit is not enough, a title plus a matching artist is, and a title plus
 * a runtime within a second or two is.
 */
object LyricsMatch {
    /** Below this a hit is a coincidence, not a match. */
    const val MIN_SCORE = 90

    private const val TITLE_EXACT = 80
    private const val TITLE_CONTAINS = 45
    private const val TITLE_PARTIAL = 25
    private const val ARTIST_EXACT = 60
    private const val ARTIST_CONTAINS = 30
    private const val ARTIST_PARTIAL = 20
    private const val DURATION_EXACT = 100
    private const val DURATION_CLOSE = 70
    private const val DURATION_NEAR = 40
    private const val DURATION_LOOSE = 15
    private const val TOKEN_OVERLAP_THRESHOLD = 0.5

    private val NON_WORD = Regex("""[^\p{L}\p{N}]""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * How much [candidateTitle] / [candidateArtist] / [candidateDurationSeconds] look like
     * [query]. A zero means the candidate cannot be the same song at all.
     */
    fun score(
        query: LyricsQuery,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationSeconds: Long?,
    ): Int {
        val titleScore = titleScore(query.title, candidateTitle ?: return 0)
        if (titleScore == 0) return 0

        var score = titleScore + artistScore(query.artist, candidateArtist.orEmpty())
        val seconds = query.durationMs / 1_000.0

        if (seconds > 0 && candidateDurationSeconds != null && candidateDurationSeconds > 0) {
            score += when (abs(candidateDurationSeconds - seconds)) {
                in 0.0..2.0 -> DURATION_EXACT
                in 2.0..5.0 -> DURATION_CLOSE
                in 5.0..15.0 -> DURATION_NEAR
                in 15.0..40.0 -> DURATION_LOOSE
                else -> 0
            }
        }
        return score
    }

    fun titleScore(queryTitle: String, candidateTitle: String): Int {
        val left = tightKey(queryTitle)
        val right = tightKey(candidateTitle)
        if (left.isEmpty() || right.isEmpty()) return 0

        return when {
            left == right -> TITLE_EXACT
            left.contains(right) || right.contains(left) -> TITLE_CONTAINS
            tokenOverlap(queryTitle, candidateTitle) >= TOKEN_OVERLAP_THRESHOLD -> TITLE_PARTIAL
            else -> 0
        }
    }

    fun artistScore(queryArtist: String, candidateArtist: String): Int {
        val left = tightKey(queryArtist)
        val right = tightKey(candidateArtist)
        if (left.isEmpty() || right.isEmpty()) return 0

        return when {
            left == right -> ARTIST_EXACT
            left.contains(right) || right.contains(left) -> ARTIST_CONTAINS
            tokenOverlap(queryArtist, candidateArtist) >= TOKEN_OVERLAP_THRESHOLD -> ARTIST_PARTIAL
            else -> 0
        }
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0

        return left.count { right.contains(it) }.toDouble() / left.size
    }

    private fun tokens(value: String): Set<String> =
        NON_WORD.replace(value.lowercase(), " ")
            .replace(WHITESPACE, " ")
            .trim()
            .split(' ')
            .filter { it.length > 1 }
            .toSet()

    private fun tightKey(value: String): String = NON_WORD.replace(value.lowercase(), "")
}
