package ca.ilianokokoro.umihi.music.models.lyrics.providers

import ca.ilianokokoro.umihi.music.BuildConfig
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.models.lyrics.Lyrics
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsProvider
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsQuery
import ca.ilianokokoro.umihi.music.models.lyrics.SyncedLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.abs

@Serializable
private data class LrcLibResponse(
    val trackName: String,
    val artistName: String,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)

private data class ScoredLyrics(val lyrics: Lyrics, val score: Int)

class LrcLibProvider(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LyricsProvider {

    override val name = "LrcLib"

    /**
     * Exact lookup first, then a scored search.
     *
     * The exact endpoint validates the duration to within a couple of seconds, so a music video
     * that runs twenty seconds longer than the album cut — or a byline that says `SonyMusicVEVO`
     * instead of the artist — misses every time. Search validates nothing, and ranking its hits
     * here is what turns those misses back into lyrics.
     */
    override suspend fun getLyrics(query: LyricsQuery): Lyrics? =
        withContext(Dispatchers.IO) {
            fetchExact(query) ?: fetchSearch(query)
        }

    private fun fetchExact(query: LyricsQuery): Lyrics? =
        try {
            val request = Request.Builder()
                .header("User-Agent", USER_AGENT)
                .url(buildGetUrl(query)).build()
            UmihiHttpClient.client.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_NOT_FOUND -> null
                    !response.isSuccessful -> null
                    else -> {
                        response.body?.string()
                            ?.let { json.decodeFromString<LrcLibResponse>(it).toLyrics() }
                    }
                }
            }
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }

    private fun fetchSearch(query: LyricsQuery): Lyrics? {
        val urls = buildList {
            if (query.artist.isNotBlank()) add(buildSearchUrl(query, includeArtist = true))
            add(buildSearchUrl(query, includeArtist = false))
        }

        for (url in urls) {
            val results = requestSearch(url) ?: continue
            val best = results
                .mapNotNull { response ->
                    val score = scoreOf(query, response)
                    if (score < MIN_MATCH_SCORE) {
                        null
                    } else {
                        response.toLyrics()?.let { ScoredLyrics(it, score) }
                    }
                }
                .maxByOrNull { it.score }

            if (best != null) {
                return best.lyrics
            }
        }
        return null
    }

    private fun requestSearch(url: HttpUrl): List<LrcLibResponse>? =
        try {
            val request = Request.Builder()
                .header("User-Agent", USER_AGENT)
                .url(url).build()
            UmihiHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body?.string()
                        ?.let { json.decodeFromString<List<LrcLibResponse>>(it) }
                }
            }
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }

    /**
     * How much a search hit looks like the song we are playing. A title has to overlap at all
     * before duration and artist get a say, which keeps a coincidentally identical runtime from
     * pairing an unrelated track with our lyrics.
     */
    private fun scoreOf(query: LyricsQuery, candidate: LrcLibResponse): Int {
        val titleScore = titleScore(query.title, candidate.trackName)
        if (titleScore == 0) return 0

        var score = titleScore + artistScore(query.artist, candidate.artistName)
        val seconds = query.durationMs / 1_000.0

        if (seconds > 0 && candidate.duration != null && candidate.duration > 0) {
            score += when (abs(candidate.duration - seconds)) {
                in 0.0..2.0 -> DURATION_EXACT
                in 2.0..5.0 -> DURATION_CLOSE
                in 5.0..15.0 -> DURATION_NEAR
                in 15.0..40.0 -> DURATION_LOOSE
                else -> 0
            }
        }
        return score
    }

    private fun titleScore(queryTitle: String, candidateTitle: String): Int {
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

    private fun artistScore(queryArtist: String, candidateArtist: String): Int {
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
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .split(' ')
            .filter { it.length > 1 }
            .toSet()

    private fun tightKey(value: String): String = NON_WORD.replace(value.lowercase(), "")

    private fun buildGetUrl(query: LyricsQuery): HttpUrl {
        val builder = GET_URL.newBuilder()
            .addQueryParameter(PARAM_TRACK, query.title)

        if (query.artist.isNotBlank()) {
            builder.addQueryParameter(PARAM_ARTIST, query.artist)
        }
        // A missing duration used to be sent as 0, which no stored track can match.
        if (query.durationMs > 0) {
            builder.addQueryParameter(PARAM_DURATION, (query.durationMs / MS_PER_SECOND).toString())
        }
        return builder.build()
    }

    private fun buildSearchUrl(query: LyricsQuery, includeArtist: Boolean): HttpUrl {
        val builder = SEARCH_URL.newBuilder()
            .addQueryParameter(PARAM_TRACK, query.title)

        if (includeArtist && query.artist.isNotBlank()) {
            builder.addQueryParameter(PARAM_ARTIST, query.artist)
        }
        return builder.build()
    }

    private fun LrcLibResponse.toLyrics(): Lyrics? {
        val lines = parseSyncedLyrics(this.syncedLyrics)

        if (lines.isEmpty()) {
            return null
        }

        return Lyrics(
            lines = lines,
            unsyncedLyrics = this.plainLyrics
        )
    }

    private fun parseSyncedLyrics(raw: String?): List<SyncedLine> {
        if (raw.isNullOrBlank()) {
            return listOf()
        }

        return raw.lineSequence()
            .mapNotNull { LRC_LINE_REGEX.matchEntire(it.trim()) }
            .map { match ->
                val (min, sec, frac, text) = match.destructured
                val fracMs = if (frac.length == 2) frac.toLong() * 10 else frac.toLong()
                SyncedLine(
                    timeMs = min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs,
                    text = text.trim()
                )
            }
            .toList()
    }

    companion object {
        private const val USER_AGENT =
            "umihi-music v${BuildConfig.VERSION_NAME} (https://github.com/ilianoKokoro/umihi-music)"
        private val GET_URL = "https://lrclib.net/api/get".toHttpUrl()
        private val SEARCH_URL = "https://lrclib.net/api/search".toHttpUrl()
        private const val PARAM_TRACK = "track_name"
        private const val PARAM_ARTIST = "artist_name"
        private const val PARAM_DURATION = "duration"
        private const val MS_PER_SECOND = 1_000
        private const val HTTP_NOT_FOUND = 404

        private const val MIN_MATCH_SCORE = 80
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

        private val LRC_LINE_REGEX = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})](.*)$""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
