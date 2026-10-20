package ca.ilianokokoro.umihi.music.models.lyrics

/**
 * The plain LRC dialect every Chinese lyrics service speaks.
 *
 * The parser lrclib needed is deliberately strict because lrclib's output is clean. What comes
 * back from the domestic services is not: a UTF-8 BOM, bracketed metadata (`[ti:]`, `[hash:]`,
 * `[total:]`), several timestamps in front of a single line when the chorus repeats, and both
 * two- and three-digit fractions. Metadata tags have letters before their colon, so they never
 * match [TIMESTAMP] and fall out with the rest of the unparseable lines.
 */
object LrcParser {
    /**
     * A file carrying a single timed line is the instrumental notice ("纯音乐，请欣赏") rather
     * than lyrics, and showing it in place of lyrics reads as a bug.
     */
    const val MIN_USABLE_LINES = 2

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_FRACTION_DIGITS = 3

    fun parse(raw: String?): List<SyncedLine> {
        if (raw.isNullOrBlank()) return emptyList()

        val lines = ArrayList<SyncedLine>()
        for (line in raw.removePrefix("\uFEFF").lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val marks = TIMESTAMP.findAll(trimmed).toList()
            if (marks.isEmpty()) continue

            val text = trimmed.substring(marks.last().range.last + 1).trim()
            if (text.isEmpty()) continue

            for (mark in marks) {
                lines += SyncedLine(timeMs = mark.toMillis(), text = text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fraction = groupValues[3]

        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(MAX_FRACTION_DIGITS).toLong()
        }
        return minutes * MILLIS_PER_MINUTE + seconds * MILLIS_PER_SECOND + millis
    }
}
