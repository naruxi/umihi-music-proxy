package ca.ilianokokoro.umihi.music.models.lyrics.providers

import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.models.lyrics.LrcParser
import ca.ilianokokoro.umihi.music.models.lyrics.Lyrics
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsMatch
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsProvider
import ca.ilianokokoro.umihi.music.models.lyrics.LyricsQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * NetEase Cloud Music.
 *
 * Worth keeping next to QQ Music rather than instead of it: the two catalogues overlap only
 * partly (each has exclusives the other cannot return at all), and NetEase's user-uploaded
 * translations are occasionally the only synced copy of a song.
 *
 * An instrumental answers `lyric: ""` instead of failing, so an empty string has to be treated
 * as a miss rather than parsed into zero lines and reported as "no lyrics".
 */
class NetEaseProvider(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LyricsProvider {

    override val name = "网易云音乐"

    override val maxCandidates = 2

    override suspend fun getLyrics(query: LyricsQuery): Lyrics? =
        withContext(Dispatchers.IO) {
            val id = bestMatch(query) ?: return@withContext null
            val raw = fetchLyric(id) ?: return@withContext null
            val lines = LrcParser.parse(raw)
            if (lines.size < LrcParser.MIN_USABLE_LINES) {
                null
            } else {
                Lyrics(lines = lines, unsyncedLyrics = raw)
            }
        }

    private fun bestMatch(query: LyricsQuery): String? {
        val url = SEARCH_URL.newBuilder()
            .addQueryParameter("s", query.keyword)
            .addQueryParameter("type", "1")
            .addQueryParameter("offset", "0")
            .addQueryParameter("limit", HITS_PER_QUERY)
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            val songs = json.parseToJsonElement(body).obj("result").list("songs")
            songs.mapNotNull { song ->
                val id = song.str("id") ?: return@mapNotNull null
                val artist = song.list("artists").joinToString("/") { it.str("name").orEmpty() }
                val score = LyricsMatch.score(
                    query = query,
                    candidateTitle = song.str("name"),
                    candidateArtist = artist,
                    // Milliseconds here, seconds everywhere else.
                    candidateDurationSeconds = song.long("duration")?.div(MS_PER_SECOND),
                )
                if (score < LyricsMatch.MIN_SCORE) null else id to score
            }.maxByOrNull { it.second }?.first
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }

    private fun fetchLyric(id: String): String? {
        val url = LYRIC_URL.newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("lv", "1")
            .addQueryParameter("kv", "0")
            .addQueryParameter("tv", "0")
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            json.parseToJsonElement(body).obj("lrc").str("lyric")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }


    private companion object {
        const val HITS_PER_QUERY = "10"
        const val MS_PER_SECOND = 1_000L
        val SEARCH_URL: HttpUrl = "https://music.163.com/api/search/get".toHttpUrl()
        val LYRIC_URL: HttpUrl = "https://music.163.com/api/song/lyric".toHttpUrl()
        const val REFERER = "https://music.163.com/"
    }
}
