package ca.ilianokokoro.umihi.music.data.datasources.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSongDataSource {
    @Upsert
    suspend fun create(song: Song)

    @Upsert
    suspend fun createAll(songs: List<Song>)

    @Query(
        """
    SELECT * 
    FROM songs 
    WHERE audioFilePath IS NOT NULL 
      AND thumbnailPath IS NOT NULL
      ORDER BY  
        songs.title COLLATE NOCASE ASC,
        songs.artists COLLATE NOCASE ASC
"""
    )
    suspend fun getDownloadedSongs(): List<Song>

    @Query(
        """
    SELECT COUNT(*)
    FROM songs
    WHERE audioFilePath IS NOT NULL
      AND thumbnailPath IS NOT NULL
"""
    )
    suspend fun countDownloadedSongs(): Int

    @Query(
        """
    SELECT COUNT(*)
    FROM songs
    WHERE audioFilePath IS NOT NULL
      AND thumbnailPath IS NOT NULL
"""
    )
    fun countDownloadedSongsFlow(): Flow<Int>

    @Query(
        """
    SELECT *
    FROM songs
    WHERE (title LIKE '%' || :query || '%'
        OR artists LIKE '%' || :query || '%')
      AND audioFilePath IS NOT NULL
      AND thumbnailPath IS NOT NULL
    ORDER BY title COLLATE NOCASE ASC
"""
    )
    suspend fun searchDownloaded(query: String): List<Song>

    @Query("SELECT * FROM songs WHERE youtubeId = :songId")
    suspend fun getSong(songId: String): Song?

    @Query("SELECT * FROM songs WHERE youtubeId IN (:songIds)")
    suspend fun getSongsByYoutubeIds(songIds: List<String>): List<Song>

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    /**
     * Caches the resolved stream url, but only against a row that already exists.
     *
     * This used to insert a placeholder keyed on the id alone whenever the track was not in the
     * library — which is every track played straight out of search results. The placeholder
     * carried an empty title, artist, duration and thumbnail, and `create` upserts the whole
     * row, so downloading that same track afterwards reused the placeholder: the file landed as
     * `-  [id].webm` and the downloaded list showed a blank name. Playback does not need the
     * library row to remember a url — `YoutubeDataSourceFactory` already keeps one in memory for
     * the life of the process.
     */
    @Query("UPDATE songs SET streamUrl = :streamUrl WHERE youtubeId = :songId")
    suspend fun setStreamUrl(songId: String, streamUrl: String)

    @Query("DELETE FROM songs WHERE youtubeId IN (:songIds)")
    suspend fun deleteByIds(songIds: List<String>)

    @Delete
    suspend fun delete(song: Song)

    @Query("UPDATE songs SET audioFilePath = :audioPath WHERE youtubeId = :songId")
    suspend fun updateAudioPath(songId: String, audioPath: String?)

}