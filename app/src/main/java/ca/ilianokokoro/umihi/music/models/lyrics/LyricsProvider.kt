package ca.ilianokokoro.umihi.music.models.lyrics


interface LyricsProvider {
    val name: String

    /**
     * How many candidate spellings this source is worth asking.
     *
     * A keyword search costs a round trip per attempt, and the domestic services answer the
     * scrubbed title about as well as the raw one, so they ask twice at most. lrclib is precise
     * per attempt, so it keeps the full list.
     */
    val maxCandidates: Int get() = 3

    suspend fun getLyrics(query: LyricsQuery): Lyrics?

    /** First hit across [queries], best spelling first. */
    suspend fun getLyrics(queries: List<LyricsQuery>): Lyrics? {
        for (query in queries.take(maxCandidates)) {
            getLyrics(query)?.let { return it }
        }
        return null
    }
}
