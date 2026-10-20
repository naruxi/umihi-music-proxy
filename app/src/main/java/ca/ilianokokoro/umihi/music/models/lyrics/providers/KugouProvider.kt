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
 * Kugou.
 *
 * Three requests instead of two, because the lyrics endpoint is keyed on the audio file's hash
 * rather than on a song id: search yields a `hash`, `krcs` turns that hash into a lyric
 * `accesskey`, and the download endpoint finally hands over the LRC.
 *
 * That chain is why this provider sits last among the domestic ones — it is the most steps for
 * the least extra catalogue — but it is also the one that still has synced lyrics for a fair
 * number of older Chinese releases the other two have dropped.
 */
class KugouProvider(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LyricsProvider {

    override val name = "酷狗"

    override val maxCandidates = 2

    override suspend fun getLyrics(query: LyricsQuery): Lyrics? =
        withContext(Dispatchers.IO) {
            val song = bestMatch(query) ?: return@withContext null
            val ticket = lyricTicket(song) ?: return@withContext null
            val raw = download(ticket) ?: return@withContext null
            val lines = LrcParser.parse(raw)
            if (lines.size < LrcParser.MIN_USABLE_LINES) {
                null
            } else {
                Lyrics(lines = lines, unsyncedLyrics = raw)
            }
        }

    private class Song(val hash: String, val durationMs: Long)

    private class Ticket(val id: String, val accessKey: String)

    private fun bestMatch(query: LyricsQuery): Song? {
        val url = SEARCH_URL.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("keyword", query.keyword)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", HITS_PER_QUERY)
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            val songs = json.parseToJsonElement(body).obj("data").list("info")
            songs.mapNotNull { song ->
                val hash = song.str("hash") ?: return@mapNotNull null
                val seconds = song.long("duration")
                val score = LyricsMatch.score(
                    query = query,
                    candidateTitle = song.str("songname"),
                    candidateArtist = song.str("singername"),
                    candidateDurationSeconds = seconds,
                )
                if (score < LyricsMatch.MIN_SCORE) {
                    null
                } else {
                    Song(hash = hash, durationMs = (seconds ?: 0L) * MS_PER_SECOND) to score
                }
            }.maxByOrNull { it.second }?.first
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }

    private fun lyricTicket(song: Song): Ticket? {
        val url = CANDIDATE_URL.newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "mobi")
            .addQueryParameter("keyword", "")
            .addQueryParameter("duration", song.durationMs.toString())
            .addQueryParameter("hash", song.hash)
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            json.parseToJsonElement(body).list("candidates").firstNotNullOfOrNull { candidate ->
                val id = candidate.str("id") ?: return@firstNotNullOfOrNull null
                val accessKey = candidate.str("accesskey") ?: return@firstNotNullOfOrNull null
                Ticket(id = id, accessKey = accessKey)
            }
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }

    private fun download(ticket: Ticket): String? {
        val url = DOWNLOAD_URL.newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", ticket.id)
            .addQueryParameter("accesskey", ticket.accessKey)
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .build()

        val body = LyricsHttp.get(url, REFERER) ?: return null

        return try {
            val encoded = json.parseToJsonElement(body).str("content")
            if (encoded.isNullOrBlank()) null else LyricsHttp.decodeBase64(encoded)
        } catch (e: Exception) {
            LogHelper.printe(e.message.toString())
            null
        }
    }


    private companion object {
        const val HITS_PER_QUERY = "10"
        const val MS_PER_SECOND = 1_000L
        /**
         * `mobiles`, not the `mobilecdn` host the docs and most snippets use.
         *
         * `mobilecdn.kugou.com` resolves into a Tencent CDN edge that answers with a
         * `*.cdn.myqcloud.com` certificate, which cannot validate for that hostname — every
         * lookup died in the TLS handshake, which is why this source never returned anything
         * on a device while the rest of the chain was fine. `mobiles` serves the same payload
         * under a valid `*.kugou.com` certificate.
         */
        val SEARCH_URL: HttpUrl = "https://mobiles.kugou.com/api/v3/search/song".toHttpUrl()
        val CANDIDATE_URL: HttpUrl = "https://krcs.kugou.com/search".toHttpUrl()
        val DOWNLOAD_URL: HttpUrl = "https://lyrics.kugou.com/download".toHttpUrl()
        const val REFERER = "https://www.kugou.com/"
    }
}
