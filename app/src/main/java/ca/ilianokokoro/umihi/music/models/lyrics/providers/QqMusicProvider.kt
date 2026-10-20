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
 * QQ Music.
 *
 * The widest catalogue of the three domestic services, which matters here because the YouTube
 * Music catalogue this app can no longer reach was the main source of Chinese pop. Two requests:
 * the search returns a `songmid`, and `musicu.fcg` answers with the LRC in base64.
 *
 * The `songmid` from the search result has to be used, not `media_mid` — they are different
 * identifiers, and the lyric endpoint silently answers `code: 24001` with an empty lyric when
 * handed the wrong one.
 */
class QqMusicProvider(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LyricsProvider {

    override val name = "QQ音乐"

    override val maxCandidates = 2

    override suspend fun getLyrics(query: LyricsQuery): Lyrics? =
        withContext(Dispatchers.IO) {
            val mid = bestMatch(query) ?: return@withContext null
            val raw = fetchLyric(mid) ?: return@withContext null
            val lines = LrcParser.parse(raw)
            if (lines.size < LrcParser.MIN_USABLE_LINES) {
                null
            } else {
                Lyrics(lines = lines, unsyncedLyrics = raw)
            }
        }

    private fun bestMatch(query: LyricsQuery): String? {
        val url = SEARCH_URL.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("n", HITS_PER_QUERY)
            .addQueryParameter("p", "1")
            .addQueryParameter("t", "0")
            .addQueryParameter("w", query.keyword)
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            val songs = json.parseToJsonElement(body).obj("data").obj("song").list("list")
            songs.mapNotNull { song ->
                val mid = song.str("songmid") ?: return@mapNotNull null
                val artist = song.list("singer").joinToString("/") { it.str("name").orEmpty() }
                val score = LyricsMatch.score(
                    query = query,
                    candidateTitle = song.str("songname"),
                    candidateArtist = artist,
                    candidateDurationSeconds = song.long("interval"),
                )
                if (score < LyricsMatch.MIN_SCORE) null else mid to score
            }.maxByOrNull { it.second }?.first
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }

    private fun fetchLyric(mid: String): String? {
        val url = LYRIC_URL.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("data", lyricPayload(mid))
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            val encoded = json.parseToJsonElement(body).obj("req").obj("data").str("lyric")
            if (encoded.isNullOrBlank()) null else LyricsHttp.decodeBase64(encoded)
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }

    private fun lyricPayload(mid: String): String = """
        {"comm":{"ct":24,"cv":0},"req":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"$mid","songID":0}}}
    """.trimIndent()


    private companion object {
        const val HITS_PER_QUERY = "10"
        val SEARCH_URL: HttpUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl()
        val LYRIC_URL: HttpUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl()
        const val REFERER = "https://y.qq.com/"
    }
}
