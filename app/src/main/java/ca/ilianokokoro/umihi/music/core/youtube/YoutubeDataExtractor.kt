package ca.ilianokokoro.umihi.music.core.youtube

import android.content.Context
import android.widget.Toast
import androidx.core.net.toUri
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper.safeArray
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper.safeObject
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.datasources.SongDataSource
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.extensions.getClientName
import ca.ilianokokoro.umihi.music.models.AddToPlaylistOption
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.PlaylistType
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import ca.ilianokokoro.umihi.music.models.lyrics.HanziFold
import ca.ilianokokoro.umihi.music.models.lyrics.TrackCredits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

var visitorData: String? = null

object YoutubeDataExtractor {
    private const val AUTO_GENERATED_HEADER = "Provided to YouTube by"
    private const val CREDIT_SEPARATOR_CHAR = '·'
    private val CREDIT_SEPARATOR = Regex("""\s*·\s*""")

    /**
     * Replacement ids already found, so a track that plays often is searched once.
     *
     * Concurrent because the playlist worker downloads several songs at a time.
     */
    private val replacementIds = ConcurrentHashMap<String, String>()

    /**
     * How far a replacement's duration may drift from the original, in seconds.
     *
     * Re-uploads keep the full track, so a few seconds of intro or silence is the most that
     * legitimately differs. A cover or a live cut lands well outside this and is dropped.
     */
    private const val REPLACEMENT_MAX_DURATION_DELTA = 15

    /** Titles that name a derivative rather than the recording itself. */
    private val REPLACEMENT_DERIVATIVE = Regex(
        """\b(cover|karaoke|instrumental|remix|nightcore|sped\s*up|slowed|reverb|8d|parody|tribute|ai\s+cover)\b""" +
                "|翻唱|伴奏|纯音乐|純音樂|dj版|加速版|慢速版",
        RegexOption.IGNORE_CASE
    )

    /**
     * The bracket characters themselves, not their contents.
     *
     * Stripping a bracketed group whole looks tidier — it removes "(Official Music Video)" —
     * but it also removes the *title* whenever the title is what the brackets carry, which is
     * the usual layout for Chinese releases: "徐佳瑩 LaLa【一樣的月光】Official Music Video".
     * Keying on the brackets alone keeps "一样的月光" in the haystack and still lets the
     * punctuation regex below flatten the noise around it.
     */
    private val BRACKET_CHARS = Regex("""[\(\)\[\]【】［］（）]""")

    /** Everything that is not a letter or a digit. */
    private val NON_WORD = Regex("""[^\p{L}\p{N}]""")

    /** Separators the artist field uses for collaborations. */
    private val ARTIST_SEPARATOR = Regex("""[/,&、]""")

    /** ` - `, ` – `, ` — ` and ` / `, which stored titles use to append an alias. */
    private val TITLE_SEGMENT_SEPARATOR = Regex("""\s+[-–—/]\s+""")

    fun extractYouTubeVideoId(url: String): String? {
        val uri = url.toUri()

        return when {
            uri.host?.contains("youtu.be") == true -> uri.lastPathSegment
            uri.host?.contains("youtube.com") == true || uri.host?.contains("music.youtube.com") == true -> uri.getQueryParameter(
                "v"
            )

            else -> null
        }
    }

    private val THUMBNAIL_KEYS = listOf(
        "musicThumbnailRenderer",
        "croppedSquareThumbnailRenderer",
        "thumbnailRenderer",
        "thumbnail",
        "thumbnails",
        "foregroundThumbnail",
    )

    fun getBestThumbnailUrl(element: JsonElement?): String {
        when (element) {
            is JsonArray -> element.asReversed().forEach { item ->
                getBestThumbnailUrl(item).takeIf { it.isNotBlank() }?.let { return it }
            }

            is JsonObject -> {
                THUMBNAIL_KEYS.forEach { key ->
                    getBestThumbnailUrl(element[key]).takeIf { it.isNotBlank() }?.let { return it }
                }
                element["url"]?.jsonPrimitive?.contentOrNull?.let { return it }
            }

            else -> Unit
        }
        return ""
    }

    fun getSongInfo(songMap: JsonElement, songInfoIndex: SongInfoType): String {
        return songMap.safeObject()
            ?.get("flexColumns")
            ?.safeArray()
            ?.getOrNull(songInfoIndex.index)
            ?.safeObject()
            ?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.safeObject()
            ?.get("text")
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()
            ?.mapNotNull { run ->
                run.safeObject()
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            ?.let { runs ->
                when (songInfoIndex) {
                    SongInfoType.TITLE ->
                        runs.joinToString("")

                    SongInfoType.ARTISTS ->
                        runs
                            .takeWhile { !it.contains(" • ") }
                            .joinToString("")
                }
            }
            ?: ""
    }

    suspend fun extractPlaylists(
        jsonString: String,
        settings: UmihiSettings
    ): List<PlaylistInfo> {
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val playlistInfos = mutableListOf<PlaylistInfo>()

        suspend fun parseGridRenderer(renderer: JsonObject) {
            renderer["items"]
                ?.safeArray()
                ?.forEach { item ->
                    parsePlaylistItem(item)?.let { playlist ->
                        playlistInfos.add(playlist)
                    }
                }

            val continuationToken = renderer["continuations"]
                ?.safeArray()
                ?.firstOrNull()
                ?.safeObject()
                ?.get("nextContinuationData")
                ?.safeObject()
                ?.get("continuation")
                ?.jsonPrimitive
                ?.contentOrNull

            if (continuationToken != null) {
                val continuationJson = YoutubeApiClient.requestContinuation(
                    continuationToken = continuationToken,
                    settings = settings,
                    //   fields = Constants.YoutubeApi.Browse.Fields.PLAYLISTS_CONTINUATION,
                )
                playlistInfos.addAll(
                    extractPlaylists(
                        jsonString = continuationJson,
                        settings = settings
                    )
                )
            }
        }

        suspend fun parseMusicLibraryRenderer(renderer: JsonObject) {
            renderer["contents"]
                ?.safeArray()
                ?.forEach { item ->
                    parseMusicLibraryItem(item)?.let { playlist ->
                        playlistInfos.add(playlist)
                    }
                }

            val continuationToken = renderer["continuations"]
                ?.safeArray()
                ?.firstOrNull()
                ?.safeObject()
                ?.get("nextContinuationData")
                ?.safeObject()
                ?.get("continuation")
                ?.jsonPrimitive
                ?.contentOrNull

            if (continuationToken != null) {
                val continuationJson = YoutubeApiClient.requestContinuation(
                    continuationToken = continuationToken,
                    settings = settings,
                    //  fields = Constants.YoutubeApi.Browse.Fields.PLAYLISTS_CONTINUATION,
                )
                playlistInfos.addAll(
                    extractPlaylists(
                        jsonString = continuationJson,
                        settings = settings
                    )
                )
            }
        }

        val tabs = json["contents"]
            ?.safeObject()
            ?.get("singleColumnBrowseResultsRenderer")
            ?.safeObject()
            ?.get("tabs")
            ?.safeArray()
            ?: json["contents"]
                ?.safeObject()
                ?.get("twoColumnBrowseResultsRenderer")
                ?.safeObject()
                ?.get("tabs")
                ?.safeArray()

        if (tabs != null) {
            val selectedTab = tabs
                .firstOrNull {
                    it.safeObject()?.get("tabRenderer")
                        ?.safeObject()
                        ?.get("selected")
                        ?.jsonPrimitive
                        ?.booleanOrNull == true
                }
                ?.safeObject()?.get("tabRenderer")?.safeObject()

            val sectionList = selectedTab
                ?.get("content")
                ?.safeObject()
                ?.get("sectionListRenderer")
                ?.safeObject()
                ?.get("contents")
                ?.safeArray()

            if (sectionList != null) {
                for (section in sectionList) {
                    val musicLibrary =
                        section.safeObject()?.get("musicLibraryRenderer")?.safeObject()
                    if (musicLibrary != null) {
                        parseMusicLibraryRenderer(musicLibrary)
                        continue
                    }

                    val gridRenderer = section.safeObject()?.get("gridRenderer")?.safeObject()
                    if (gridRenderer != null) {
                        parseGridRenderer(gridRenderer)
                        continue
                    }

                    val nestedSectionList = section.safeObject()
                        ?.get("sectionListRenderer")?.safeObject()
                        ?.get("contents")?.safeArray()
                    if (nestedSectionList != null) {
                        for (nested in nestedSectionList) {
                            val nestedMusicLibrary =
                                nested.safeObject()?.get("musicLibraryRenderer")?.safeObject()
                            if (nestedMusicLibrary != null) {
                                parseMusicLibraryRenderer(nestedMusicLibrary)
                                continue
                            }
                            val nestedGrid =
                                nested.safeObject()?.get("gridRenderer")?.safeObject()
                            if (nestedGrid != null) {
                                parseGridRenderer(nestedGrid)
                                continue
                            }
                        }
                    }
                }
            } else if (selectedTab != null) {
                val tabContent = selectedTab["content"]?.safeObject()
                val directMusicLibrary = tabContent?.get("musicLibraryRenderer")?.safeObject()
                if (directMusicLibrary != null) {
                    parseMusicLibraryRenderer(directMusicLibrary)
                }
                val directGrid = tabContent?.get("gridRenderer")?.safeObject()
                if (directGrid != null) {
                    parseGridRenderer(directGrid)
                }
                if (directMusicLibrary == null && directGrid == null) {
                    printd("extractPlaylists: tab content has no recognizable renderer. Keys: ${tabContent?.keys}")
                }
            }
        } else {
            printd("extractPlaylists: no tabbed browse result found (may be continuation response)")
        }

        val gridContinuation = json["continuationContents"]
            ?.safeObject()
            ?.get("gridContinuation")
            ?.safeObject()
        if (gridContinuation != null) {
            parseGridRenderer(gridContinuation)
        }

        val musicLibraryContinuation = json["continuationContents"]
            ?.safeObject()
            ?.get("musicLibraryContinuation")
            ?.safeObject()
        if (musicLibraryContinuation != null) {
            parseMusicLibraryRenderer(musicLibraryContinuation)
        }

        if (playlistInfos.isEmpty() && tabs == null && gridContinuation == null && musicLibraryContinuation == null) {
            printd("extractPlaylists: no playlists found in any recognizable structure. Root keys: ${json.keys}")
        }

        return playlistInfos.distinctBy {
            it.id.removePrefix("VL")
        }
    }

    private fun parsePlaylistItem(item: JsonElement): PlaylistInfo? {
        val playlistRenderer = item.safeObject()?.get("musicTwoRowItemRenderer")
            ?.safeObject()
            ?: return null

        val navigationEndpoint = playlistRenderer["navigationEndpoint"]
            ?.safeObject()
            ?: return null

        val isCreatePlaylistTile =
            navigationEndpoint["createPlaylistEndpoint"] != null

        if (isCreatePlaylistTile) {
            return null
        }

        val browseId = navigationEndpoint["browseEndpoint"]
            ?.safeObject()
            ?.get("browseId")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val title = playlistRenderer["title"]
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()
            ?.getOrNull(0)
            ?.safeObject()
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val subtitle = playlistRenderer["subtitle"]
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()

        val thumbnailRenderer = playlistRenderer["thumbnailRenderer"]
            ?: return null

        val thumbnailUrl = getBestThumbnailUrl(thumbnailRenderer)

        val playlistInfo = PlaylistInfo(
            id = browseId,
            title = title,
            coverHref = thumbnailUrl,
            type = detectPlaylistType(playlistRenderer)
        )
        playlistInfo.songCount = extractTrackCount(subtitle)
        return playlistInfo
    }

    private fun parseMusicLibraryItem(item: JsonElement): PlaylistInfo? {
        val renderer = item.safeObject()?.get("musicLibraryItemRenderer")
            ?.safeObject()
            ?: return null

        val navigationEndpoint = renderer["navigationEndpoint"]
            ?.safeObject()
            ?: return null

        val browseId = navigationEndpoint["browseEndpoint"]
            ?.safeObject()
            ?.get("browseId")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val title = renderer["title"]
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()
            ?.getOrNull(0)
            ?.safeObject()
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val thumbnailRenderer = renderer["thumbnailRenderer"]
            ?: return null

        val thumbnailUrl = getBestThumbnailUrl(thumbnailRenderer)

        val playlistInfo = PlaylistInfo(
            id = browseId,
            title = title,
            coverHref = thumbnailUrl,
            type = detectPlaylistType(renderer)
        )

        val subtitle = renderer["subtitle"]
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()

        playlistInfo.songCount = extractTrackCount(subtitle)
        return playlistInfo
    }


    private fun detectPlaylistType(renderer: JsonObject): PlaylistType {
        val menuItems = renderer["menu"]
            ?.safeObject()
            ?.get("menuRenderer")
            ?.safeObject()
            ?.get("items")
            ?.safeArray()
            ?: return PlaylistType.AUTOGENERATED

        var saved = false
        for (item in menuItems) {
            val itemObject = item.safeObject() ?: continue

            val navigationRenderer = itemObject["menuNavigationItemRenderer"]?.safeObject()
            val navigationEndpoint = navigationRenderer?.get("navigationEndpoint")?.safeObject()

            if (navigationEndpoint?.get("playlistEditorEndpoint") != null ||
                navigationEndpoint?.get("playlistDeleteEndpoint") != null
            ) {
                return PlaylistType.CREATED_BY_USER
            }

            if (isLibrarySaveToggle(itemObject["toggleMenuServiceItemRenderer"]?.safeObject())) {
                saved = true
            }

            val serviceEndpoint = itemObject["menuServiceItemRenderer"]
                ?.safeObject()
                ?.get("serviceEndpoint")
                ?.safeObject()

            val confirmEndpoint = navigationEndpoint
                ?.get("confirmDialogEndpoint")?.safeObject()
                ?.get("confirmEndpoint")?.safeObject()

            if (isLibraryRemoveEndpoint(serviceEndpoint) ||
                isLibraryRemoveEndpoint(navigationEndpoint) ||
                isLibraryRemoveEndpoint(confirmEndpoint)
            ) {
                saved = true
            }

            val iconType =
                (navigationRenderer ?: itemObject["menuServiceItemRenderer"]?.safeObject())
                    ?.get("icon")?.safeObject()
                    ?.get("iconType")?.jsonPrimitive?.contentOrNull
            if (iconType == "LIBRARY_REMOVE") {
                saved = true
            }
        }

        return if (saved) PlaylistType.SAVED else PlaylistType.AUTOGENERATED
    }

    private fun isLibraryRemoveEndpoint(endpoint: JsonObject?): Boolean =
        endpoint?.keys?.any { key ->
            key.contains("Library", ignoreCase = true) && key.contains("Remove", ignoreCase = true)
        } == true

    private fun isLibrarySaveToggle(toggleRenderer: JsonObject?): Boolean {
        if (toggleRenderer?.get("isToggled")?.jsonPrimitive?.booleanOrNull != true) {
            return false
        }

        val hasLikeEndpoint = toggleRenderer["toggledServiceEndpoint"]
            ?.safeObject()?.get("likeEndpoint") != null
        return hasLikeEndpoint || toggleRenderer["toggledText"]
            ?.safeObject()?.get("runs")?.safeArray()
            ?.any { run ->
                run.safeObject()?.get("text")?.jsonPrimitive
                    ?.contentOrNull?.contains("library", ignoreCase = true) == true
            } == true
    }


    private val ADD_TO_PLAYLIST_RENDERER_KEYS: Set<String> = setOf(
        "playlistAddToOptionRenderer",
        "addToPlaylistItemRenderer",
        "musicResponsiveListItemRenderer",
        "musicTwoRowItemRenderer",
    )

    fun extractAddToPlaylistOptions(jsonString: String): List<AddToPlaylistOption> {
        val json = Json.parseToJsonElement(jsonString).jsonObject

        val root = json["contents"]
            ?.safeArray()
            ?.firstNotNullOfOrNull { it.safeObject()?.get("addToPlaylistRenderer") }
            ?: return emptyList()

        val collected = mutableListOf<AddToPlaylistOption>()
        collectAddToPlaylistOptions(root, collected)

        val seen = mutableSetOf<String>()
        return collected
            .filter { !isLikedMusicPlaylist(it.playlistId) }
            .filter { seen.add(it.playlistId) }
    }

    private fun isLikedMusicPlaylist(playlistId: String): Boolean {
        return playlistId.removePrefix("VL") == "LM"
    }

    private fun collectAddToPlaylistOptions(
        value: JsonElement?,
        sink: MutableList<AddToPlaylistOption>
    ) {
        when (value) {
            is JsonObject -> {
                ADD_TO_PLAYLIST_RENDERER_KEYS.forEach { key ->
                    value[key]?.safeObject()?.let { renderer ->
                        parseAddToPlaylistOption(renderer)?.let { sink.add(it) }
                    }
                }
                value.forEach { (_, child) -> collectAddToPlaylistOptions(child, sink) }
            }

            is JsonArray -> value.forEach { collectAddToPlaylistOptions(it, sink) }

            else -> Unit
        }
    }

    private fun parseAddToPlaylistOption(renderer: JsonObject): AddToPlaylistOption? {
        val playlistId = findAddToPlaylistPlaylistId(renderer) ?: return null
        val title = extractOptionText(
            renderer,
            "title",
            "text",
            "label",
            "primaryText",
            "header",
            "flexColumns",
        ) ?: return null
        if (title == "Create new playlist" || title == "New playlist") {
            return null
        }

        val thumbnailUrl = getBestThumbnailUrl(renderer).takeIf { it.isNotBlank() }

        return AddToPlaylistOption(
            playlistId = playlistId,
            title = title,
            subtitle = extractOptionText(renderer, "subtitle", "secondaryText"),
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun findAddToPlaylistPlaylistId(value: JsonElement?): String? {
        when (value) {
            is JsonObject -> {
                value["playlistId"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    if (id.isNotBlank()) return id
                }
                value["browseId"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    if (id.startsWith("VL") || id.startsWith("PL")) return id
                }
                value.forEach { (_, child) ->
                    findAddToPlaylistPlaylistId(child)?.let { return it }
                }
            }

            is JsonArray -> value.forEach {
                findAddToPlaylistPlaylistId(it)?.let { id -> return id }
            }

            else -> Unit
        }
        return null
    }

    private fun extractOptionText(renderer: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            if (key == "flexColumns") {
                val text = renderer["flexColumns"]
                    ?.safeArray()
                    ?.firstOrNull()
                    ?.safeObject()
                    ?.get("musicResponsiveListItemFlexColumnRenderer")
                    ?.safeObject()
                    ?.get("text")
                extractTextValue(text)?.let { return it }
                continue
            }

            extractTextValue(renderer[key])?.let { return it }
        }
        extractTextValue(renderer["runs"])?.let { return it }
        return null
    }

    private fun extractTextValue(element: JsonElement?): String? {
        if (element is JsonArray) {
            return element.mapNotNull { run ->
                run.safeObject()?.get("text")?.jsonPrimitive?.contentOrNull
            }
                .joinToString("")
                .takeIf { it.isNotBlank() }
        }

        val obj = element?.safeObject() ?: return null
        return obj["runs"]
            ?.safeArray()
            ?.mapNotNull { run ->
                run.safeObject()?.get("text")?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: obj["simpleText"]?.jsonPrimitive?.contentOrNull
    }

    fun extractTrackCount(runs: JsonArray?): Int? {
        return runs
            ?.getOrNull(2)
            ?.safeObject()
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.substringBefore(' ')
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
    }

    fun extractCreatedPlaylist(jsonString: String): PlaylistInfo? {
        val json = Json.parseToJsonElement(jsonString).jsonObject

        val renderer = json["actions"]
            ?.safeArray()
            ?.firstNotNullOfOrNull { action ->
                action.safeObject()?.get("handlePlaylistCreationCommand")
                    ?.safeObject()
                    ?.get("createdPlaylist")
                    ?.safeObject()
                    ?.get("musicTwoRowItemRenderer")
                    ?.safeObject()
            }
            ?: return null

        val title = renderer["title"]
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()
            ?.getOrNull(0)
            ?.safeObject()
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val browseId = renderer["navigationEndpoint"]
            ?.safeObject()
            ?.get("browseEndpoint")
            ?.safeObject()
            ?.get("browseId")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val thumbnailUrl = getBestThumbnailUrl(
            renderer["thumbnailRenderer"] ?: return null
        )

        val playlistInfo = PlaylistInfo(
            id = browseId,
            title = title,
            coverHref = thumbnailUrl,
            type = PlaylistType.CREATED_BY_USER
        )
        playlistInfo.songCount = 0
        return playlistInfo
    }

    fun extractSearchAutocompleteResults(jsonString: String): List<String> {
        val json = Json.parseToJsonElement(jsonString)

        return json.safeObject()
            ?.get("contents")
            ?.safeArray()
            ?.flatMap { section ->
                section.safeObject()
                    ?.get("searchSuggestionsSectionRenderer")
                    ?.safeObject()
                    ?.get("contents")
                    ?.safeArray()
                    ?.mapNotNull { item ->
                        item.safeObject()
                            ?.get("searchSuggestionRenderer")
                            ?.safeObject()
                            ?.get("suggestion")
                            ?.safeObject()
                            ?.get("runs")
                            ?.safeArray()
                            ?.mapNotNull { run ->
                                run.safeObject()
                                    ?.get("text")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                            }
                            ?.joinToString("")
                    }
                    ?: emptyList()
            }
            ?: emptyList()
    }

    fun extractSearchResults(jsonString: String): List<Song> {
        val json = Json.parseToJsonElement(jsonString).jsonObject

        val tabs = json["contents"]
            ?.safeObject()?.get("tabbedSearchResultsRenderer")
            ?.safeObject()?.get("tabs")
            ?.safeArray() ?: return emptyList()


        val selectedTab = tabs.firstOrNull {
            it.safeObject()?.get("tabRenderer")
                ?.safeObject()?.get("selected")
                ?.jsonPrimitive?.booleanOrNull == true
        }?.safeObject()?.get("tabRenderer")?.safeObject() ?: return emptyList()

        val contents = selectedTab["content"]
            ?.safeObject()?.get("sectionListRenderer")
            ?.safeObject()?.get("contents")
            ?.safeArray() ?: return emptyList()

        // Results arrive either as a top level musicShelfRenderer, or nested one level down in an
        // itemSectionRenderer — the latter whenever no search params are sent, which makes the
        // endpoint answer with the combined "top result" layout. Both shapes are walked so the
        // parser never silently comes back empty for one of them. Entries without a videoId
        // (artist, album and playlist cards) are dropped by extractSong itself.
        val songRendererList = contents.flatMap { section ->
            val sectionObject = section.safeObject() ?: return@flatMap emptyList<JsonElement>()
            (sectionObject["musicShelfRenderer"] ?: sectionObject["itemSectionRenderer"])
                ?.safeObject()?.get("contents")
                ?.safeArray()
                ?: emptyList<JsonElement>()
        }

        return songRendererList
            .mapNotNull { extractSong(it) }
            .distinctBy { it.youtubeId }
    }

    /**
     * Parses a plain YouTube search response into songs.
     *
     * The endpoint answers with `videoRenderer` items rather than the music renderers
     * [extractSearchResults] expects, so this walks the whole `contents` tree instead of a fixed
     * path — YouTube nests results differently depending on whether a shelf, a section or a
     * single card comes back. Every entry that survives carries a `videoId`, a title, the channel
     * name, a duration and a thumbnail, which is all a Song needs; playback resolves the
     * audio-only stream from that id.
     */
    fun extractWebSearchResults(jsonString: String): List<Song> {
        val root = Json.parseToJsonElement(jsonString).safeObject() ?: return emptyList()
        val renderers = mutableListOf<JsonObject>()
        collectVideoRenderers(root["contents"] ?: return emptyList(), renderers)

        return renderers
            .mapNotNull { extractWebSong(it) }
            .distinctBy { it.youtubeId }
    }

    private fun collectVideoRenderers(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "videoRenderer") {
                    value.safeObject()?.let { out.add(it) }
                } else {
                    collectVideoRenderers(value, out)
                }
            }

            is JsonArray -> element.forEach { collectVideoRenderers(it, out) }
            else -> Unit
        }
    }

    private fun extractWebSong(renderer: JsonObject): Song? {
        val videoId = renderer["videoId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        val title = readText(renderer["title"])?.takeIf { it.isNotBlank() } ?: return null
        val artists = readText(renderer["ownerText"])
            ?: readText(renderer["longBylineText"])
            ?: readText(renderer["shortBylineText"])
            ?: ""
        val duration = readText(renderer["lengthText"]) ?: ""

        return Song(
            youtubeId = videoId,
            title = title,
            artists = artists,
            duration = duration,
            thumbnailHref = getBestThumbnailUrl(renderer["thumbnail"]),
            isAvailable = renderer["upcomingEventData"] == null,
        )
    }

    /** Reads either a `runs` array or a `simpleText` field, both of which the web client uses. */
    private fun readText(element: JsonElement?): String? {
        val obj = element?.safeObject() ?: return null
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return obj["runs"]?.safeArray()
            ?.joinToString("") { run ->
                run.safeObject()?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
            }
    }


    /**
     * Reads the credit block YouTube appends to auto-generated uploads: a line of the shape
     * `Song · Artist`, followed by the album. Hand-written descriptions do not follow it, so
     * anything without the "Provided to YouTube by" header is rejected rather than guessed at.
     *
     * This matters when search handed us a channel name and a marketing title, because the
     * credits inside the description stay correct regardless of how the video was titled.
     */
    fun extractDescriptionCredits(jsonString: String): TrackCredits? {
        val description = Json.parseToJsonElement(jsonString)
            .safeObject()?.get("videoDetails")
            ?.safeObject()?.get("shortDescription")
            ?.jsonPrimitive?.contentOrNull
            ?: return null

        if (!description.contains(AUTO_GENERATED_HEADER)) return null

        val lines = description.lines().map { it.trim() }
        val creditIndex = lines.indexOfFirst { it.contains(CREDIT_SEPARATOR_CHAR) }
        if (creditIndex < 0) return null

        val parts = CREDIT_SEPARATOR.split(lines[creditIndex])
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        val album = lines.drop(creditIndex + 1)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("℗") && !it.startsWith("Released on") }
            .orEmpty()

        return TrackCredits(title = parts[0], artist = parts[1], album = album)
    }

    fun extractSongInfo(jsonString: String): Song {
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val details = json["videoDetails"]?.safeObject()

        val videoId = details?.get("videoId")?.jsonPrimitive?.contentOrNull ?: ""
        val title = details?.get("title")?.jsonPrimitive?.contentOrNull ?: ""

        val artists = details?.get("author")?.jsonPrimitive?.contentOrNull ?: ""
        val lengthSeconds: Int =
            details?.get("lengthSeconds")?.jsonPrimitive?.contentOrNull?.toInt()
                ?: 0

        val isExplicit = json["microformat"]
            ?.safeObject()?.get("microformatDataRenderer")
            ?.safeObject()?.get("familySafe")
            ?.jsonPrimitive?.booleanOrNull
            ?.let { !it }
            ?: false

        return Song(
            youtubeId = videoId,
            title = title,
            artists = artists,
            duration = formatSecondsForYouTubeDisplay(lengthSeconds),
            thumbnailHref = getBestThumbnailUrl(details?.get("thumbnail")),
            isExplicit = isExplicit
        )
    }


    suspend fun extractSongList(
        jsonString: String,
        settings: UmihiSettings,
        onProgress: (Int) -> Unit = {}
    ): List<Song> {
        val json = Json.parseToJsonElement(jsonString).jsonObject

        val sectionList = json["contents"]
            ?.safeObject()?.get("twoColumnBrowseResultsRenderer")
            ?.safeObject()?.get("secondaryContents")
            ?.safeObject()?.get("sectionListRenderer")
            ?.safeObject()

        val shelfContents = sectionList
            ?.get("contents")
            ?.safeArray()
            ?.firstNotNullOfOrNull {
                it.safeObject()?.get("musicPlaylistShelfRenderer")?.safeObject()
            }
            ?.get("contents")
            ?.safeArray()

        if (shelfContents != null) {
            return parseSongsFromContents(shelfContents, settings, onProgress)
        }

        val altContents = json["contents"]
            ?.safeObject()?.get("singleColumnBrowseResultsRenderer")
            ?.safeObject()?.get("tabs")
            ?.safeArray()?.firstOrNull()
            ?.safeObject()?.get("tabRenderer")
            ?.safeObject()?.get("content")
            ?.safeObject()?.get("sectionListRenderer")
            ?.safeObject()?.get("contents")
            ?.safeArray()?.firstOrNull()
            ?.safeObject()?.get("musicPlaylistShelfRenderer")
            ?.safeObject()?.get("contents")
            ?.safeArray()

        if (altContents != null) {
            return parseSongsFromContents(altContents, settings, onProgress)
        }

        printd("extractSongList: could not find playlist contents. Root keys: ${json.keys}")

        return emptyList()
    }

    private fun extractContinuationContents(jsonString: String): JsonArray? {
        val json = Json.parseToJsonElement(jsonString).jsonObject

        val appendItems = json["onResponseReceivedActions"]
            ?.safeArray()?.getOrNull(0)
            ?.safeObject()?.get("appendContinuationItemsAction")
            ?.safeObject()?.get("continuationItems")
            ?.safeArray()

        if (appendItems != null) {
            return appendItems
        }

        val continuationContents = json["continuationContents"]?.safeObject()

        val playlistShelfContents = continuationContents
            ?.get("musicPlaylistShelfContinuation")
            ?.safeObject()
            ?.get("contents")
            ?.safeArray()

        if (playlistShelfContents != null) {
            return playlistShelfContents
        }

        val sectionListContents = continuationContents
            ?.get("sectionListContinuation")
            ?.safeObject()
            ?.get("contents")
            ?.safeArray()

        if (sectionListContents != null) {
            val shelfContentsInSection = sectionListContents
                .firstNotNullOfOrNull {
                    it.safeObject()?.get("musicPlaylistShelfRenderer")?.safeObject()
                }
                ?.get("contents")
                ?.safeArray()
            if (shelfContentsInSection != null) {
                return shelfContentsInSection
            }
        }

        printd(
            "extractContinuationSongs: no continuationItems found. " +
                    "Keys: ${json.keys} | continuationContents keys: ${continuationContents?.keys}"
        )

        return null
    }


    private fun formatSecondsForYouTubeDisplay(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private suspend fun parseSongsFromContents(
        contents: JsonArray?,
        settings: UmihiSettings,
        onProgress: (Int) -> Unit,
        songs: MutableList<Song> = mutableListOf()
    ): List<Song> {
        var currentContents = contents

        while (true) {
            val page = currentContents ?: break
            var continuationToken: String? = null

            for (shelf in page) {
                val continuationContent = shelf.safeObject()?.get("continuationItemRenderer")

                if (continuationContent != null) {
                    val continuationObject =
                        continuationContent.safeObject()?.get("continuationEndpoint")
                            ?.safeObject()
                    continuationToken = continuationObject
                        ?.get("continuationCommand")
                        ?.safeObject()
                        ?.get("token")
                        ?.jsonPrimitive?.contentOrNull
                        ?: continuationObject
                            ?.get("commandExecutorCommand")
                            ?.safeObject()
                            ?.get("commands")
                            ?.safeArray()
                            ?.firstOrNull { it.safeObject()?.get("continuationCommand") != null }
                            ?.safeObject()
                            ?.get("continuationCommand")
                            ?.safeObject()
                            ?.get("token")
                            ?.jsonPrimitive?.contentOrNull

                    continue
                }


                val song = extractSong(shelf) ?: continue
                songs.add(
                    song
                )
                onProgress(songs.size)
            }

            currentContents = if (continuationToken != null) {
                extractContinuationContents(
                    YoutubeApiClient.requestContinuation(
                        continuationToken = continuationToken,
                        settings = settings,
                    )
                )
            } else {
                null
            }
        }

        return songs
    }

    fun extractSong(
        json: JsonElement,
    ): Song? {
        val songContent =
            json.safeObject()?.get("musicResponsiveListItemRenderer")?.safeObject() ?: return null
        val thumbnailUrl = getBestThumbnailUrl(songContent["thumbnail"] ?: return null)

        val unavailable =
            songContent["musicItemRendererDisplayPolicy"]?.jsonPrimitive?.contentOrNull == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
        val title = getSongInfo(songContent, SongInfoType.TITLE)
        val artists = getSongInfo(songContent, SongInfoType.ARTISTS)
        val videoId = songContent["playlistItemData"]
            ?.safeObject()?.get("videoId")
            ?.jsonPrimitive?.contentOrNull ?: return null

        val setVideoId = songContent["playlistItemData"]
            ?.safeObject()?.get("playlistSetVideoId")
            ?.jsonPrimitive?.contentOrNull
            ?: songContent["playlistItemData"]
                ?.safeObject()?.get("setVideoId")
                ?.jsonPrimitive?.contentOrNull
            ?: extractRemovalSetVideoId(json)


        val duration = extractDuration(songContent)

        val isExplicit = songContent["badges"]
            ?.safeArray()
            ?.any { badge ->
                badge.safeObject()
                    ?.get("musicInlineBadgeRenderer")
                    ?.safeObject()
                    ?.get("icon")
                    ?.safeObject()
                    ?.get("iconType")
                    ?.jsonPrimitive
                    ?.contentOrNull == "MUSIC_EXPLICIT_BADGE"
            } ?: false

        val isLiked = songContent["menu"]
            ?.safeObject()?.get("menuRenderer")
            ?.safeObject()?.get("topLevelButtons")
            ?.safeArray()
            ?.firstOrNull { item ->
                item.safeObject()?.get("likeButtonRenderer") != null
            }
            ?.safeObject()?.get("likeButtonRenderer")
            ?.safeObject()?.get("likeStatus")
            ?.jsonPrimitive?.contentOrNull == "LIKE"

        return Song(
            youtubeId = videoId,
            title = title,
            artists = artists,
            duration = duration,
            thumbnailHref = thumbnailUrl,
            isExplicit = isExplicit,
            isLiked = isLiked,
            isAvailable = !unavailable
        ).also { song ->
            song.setVideoId = setVideoId
        }

    }

    private fun extractRemovalSetVideoId(element: JsonElement): String? {
        val jsonObject = element.safeObject() ?: return null
        val actions = jsonObject["playlistEditEndpoint"]
            ?.safeObject()?.get("actions")
            ?.safeArray()
        if (actions != null) {
            for (action in actions) {
                val actionObject = action.safeObject() ?: continue
                if (actionObject["action"]?.jsonPrimitive?.contentOrNull == "ACTION_REMOVE_VIDEO") {
                    actionObject["setVideoId"]
                        ?.jsonPrimitive?.contentOrNull?.let { return it }
                }
            }
        }
        for (value in jsonObject.values) {
            extractRemovalSetVideoId(value)?.let { return it }
        }
        return null
    }


    suspend fun getSongPlayerUrl(
        context: Context,
        song: Song,
        allowLocal: Boolean = false
    ): String {
        val localSongRepository = AppDatabase.getInstance(context).songRepository()
        var savedSong: Song? = null
        try {
            savedSong = localSongRepository.getSong(song.youtubeId)
        } catch (ex: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.failed_get_local_song),
                Toast.LENGTH_LONG
            )
                .show()
            printe(ex.toString())
        }

        if (savedSong != null) {
            if (allowLocal && savedSong.audioFilePath != null) {
                printd("[${song.youtubeId}] Was downloaded")
                return savedSong.audioFilePath
            }

            if (savedSong.streamUrl != null) {
                if (isStreamUrlFresh(savedSong.streamUrl)) {
                    printd("[${song.youtubeId}] Got url from saved, expire still valid")
                    return savedSong.streamUrl
                }
                if (isYoutubeUrlValid(savedSong.streamUrl)) {
                    printd("[${song.youtubeId}] Got url from saved")
                    return savedSong.streamUrl
                }
                printd("[${song.youtubeId}] Saved url was invalid")
            }
        }

        // The cookies decide whether the player call is answered at all: signed out, YouTube
        // refuses uploads and library-only tracks with the same `UNPLAYABLE` it uses for a
        // region block. Reading them here keeps both callers — playback and download — on the
        // same, signed-in path without threading settings through the player factory.
        val settings = runCatching {
            DatastoreRepository(context).getSettings()
        }.getOrNull()

        val newUri = getSongUrlFromYoutube(song, settings)
        localSongRepository.setStreamUrl(songId = song.youtubeId, streamUrl = newUri)
        printd("[${song.youtubeId}] Got url from YouTube and saved song")
        return newUri
    }


    private fun extractDuration(songContent: JsonObject): String {
        val durationRegex = Regex("""\d+:\d{2}(:\d{2})?""")

        val fixedDuration = songContent["fixedColumns"]
            ?.safeArray()
            ?.firstOrNull()
            ?.safeObject()
            ?.get("musicResponsiveListItemFixedColumnRenderer")
            ?.safeObject()
            ?.get("text")
            ?.safeObject()
            ?.get("runs")
            ?.safeArray()
            ?.firstOrNull()
            ?.safeObject()
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull

        if (fixedDuration != null) {
            return fixedDuration
        }

        val flexColumns = songContent["flexColumns"]
            ?.safeArray()
            ?: return ""

        for (column in flexColumns) {
            val runs = column.safeObject()?.get("musicResponsiveListItemFlexColumnRenderer")
                ?.safeObject()
                ?.get("text")
                ?.safeObject()
                ?.get("runs")
                ?.safeArray()
                ?: continue

            for (run in runs) {
                val text = run.safeObject()?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: continue

                if (durationRegex.matches(text)) {
                    return text
                }
            }
        }

        return ""
    }

    private suspend fun getSongUrlFromYoutube(
        song: Song,
        settings: UmihiSettings? = null,
        retries: Int = Constants.YoutubeApi.NEWPIPE_TRIES
    ): String {
        var lastError: Throwable? = null

        val fastUrl = resolveAnonymousStreamUrl(song.youtubeId, settings)

        if (fastUrl != null) {
            return fastUrl
        }

        // The refusal above is final for this id, but not for the recording: the official
        // upload can be pulled while re-uploads of the same track stay playable. Ask the
        // catalogue before falling back to NewPipe, which speaks a client these exits refuse
        // just as readily.
        val replacementId = findReplacementVideoId(song)
        if (replacementId != null) {
            val replacementUrl = resolveAnonymousStreamUrl(replacementId, settings)
            if (replacementUrl != null) {
                printd("${song.youtubeId} : Resolved through replacement $replacementId")
                return replacementUrl
            }
        }

        printd("${song.youtubeId} : Falling back to NewPipe")
        repeat(retries) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    resolveNewPipeStreamUrl(song)
                }
            } catch (e: Throwable) {
                lastError = e

                printe(
                    "${song.youtubeId} : Failed attempt ${attempt + 1}/$retries with NewPipe: " +
                            "${e::class.simpleName}: ${e.message ?: "no message"}"
                )

                if (attempt < retries - 1) {
                    delay((Constants.YoutubeApi.RETRY_DELAY * (attempt + 1)).milliseconds)
                }
            }
        }

        throw Exception(
            "${song.youtubeId} : Fatal fail. Could not get it after $retries attempts",
            lastError
        )
    }

    private suspend fun resolveAnonymousStreamUrl(
        videoId: String,
        settings: UmihiSettings? = null,
    ): String? = withContext(Dispatchers.IO) {
        suspend fun executeRequest(client: JsonObject, auth: UmihiSettings?): String {
            val response = YoutubeApiClient.getPlayerInfo(
                videoId = videoId,
                client = client,
                visitorData = visitorData,
                settings = auth,
            )

            return extractStreamFromRawResponse(response)
        }

        // Two passes. The first is anonymous, which is enough for the public catalogue and
        // costs one request. The second carries the account cookies, because a signed-out
        // player call answers `UNPLAYABLE / This video is not available` for uploads, for the
        // library-only tracks, and for every video on an exit YouTube distrusts — the same
        // answer a region block gives, so the first pass cannot tell them apart.
        val passes = mutableListOf<UmihiSettings?>()
        passes.add(null)
        settings?.takeIf { !it.cookies.isEmpty() }?.let { passes.add(it) }

        var totalRequests = 0

        for (auth in passes) {
            val clients = if (auth == null) {
                Constants.YoutubeApi.Client.FALLBACK_ORDER
            } else {
                Constants.YoutubeApi.Client.AUTH_FALLBACK_ORDER
            }

            for (client in clients) {
                val clientName = client.getClientName()
                val label = if (auth == null) clientName else "$clientName+auth"
                var tries = 0

                while (tries < Constants.YoutubeApi.PLAYER_TRIES_PER_CLIENT) {
                    tries++
                    totalRequests++

                    try {
                        val stream = executeRequest(client, auth)
                        printd(
                            "[$label] Resolved stream in $totalRequests total request(s)"
                        )
                        return@withContext stream

                    } catch (ex: Exception) {
                        val message = ex.message ?: ex::class.simpleName.orEmpty()
                        printe("[${label}] $message")

                        // "Sign in to confirm you're not a bot" is a coin flip, not a verdict:
                        // measured on a flagged exit, 7 of 20 anonymous player calls were
                        // rejected and the rest went through, with the same visitor id and the
                        // same video. The old loop only retried while the response had replaced
                        // the visitor id, and these rejections echo it back unchanged, so it
                        // stopped after two attempts and fell into the NewPipe fallback — which
                        // that exit rejects too, showing up as a "source error" seconds later.
                        // Retrying with the visitor id the response just handed back is enough;
                        // clearing it would make every retry visitor-less and fail every time.
                        if (!isBotCheck(message)) break
                    }
                }
            }
        }

        null
    }

    /** True when the player endpoint refused anonymous access rather than the video. */
    private fun isBotCheck(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("not a bot") || lower.contains("sign in to confirm")
    }

    /**
     * Last resort before reporting a track as undownloadable.
     *
     * A player refusal is final for the id it was asked about, but the id can die while the
     * recording lives on: the rights holder pulls the official upload from the catalogue and
     * leaves only community re-uploads of the same recording. Measured on one such track — the
     * official `- Topic` id answered `UNPLAYABLE` from four clients, signed in and out, on three
     * different exits — the first result for "<artist> <title>" played straight away.
     *
     * The match is deliberately strict, because getting it wrong is worse than failing:
     * "Always On The Run" is both an Avicii track and a Lenny Kravitz one, and swapping in the
     * wrong recording means the user silently hears a different song. So the title has to match
     * after normalisation, the artist has to appear in the candidate's own text — search results
     * put the *channel* name in the artist field, so the title is searched too — the duration
     * has to be within seconds, and obvious derivatives are dropped by name.
     */
    private suspend fun findReplacementVideoId(song: Song): String? {
        replacementIds[song.youtubeId]?.let { return it }

        val query = listOf(song.artists, song.title)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isEmpty()) return null

        val candidates = runCatching { SongDataSource().search(query) }
            .getOrNull()
            .orEmpty()
            .filter { it.youtubeId != song.youtubeId }
        if (candidates.isEmpty()) return null

        // Stored titles often carry the artist or an English alias after a dash —
        // "隨緣 - Sui Yuan", "一生何求 - Life Expectation" — while a search result only repeats
        // one of them. Matching any segment long enough to be distinctive covers both.
        val wantedTitles = song.title
            .split(TITLE_SEGMENT_SEPARATOR)
            .map { normaliseTitle(it) }
            .filter { it.length >= 2 }
            .ifEmpty { listOf(normaliseTitle(song.title)) }
            .filter { it.isNotEmpty() }
        if (wantedTitles.isEmpty()) return null
        val wantedSeconds = parseDurationSeconds(song.duration)
        val wantedArtist = normaliseArtist(song.artists)

        val best = candidates
            .asSequence()
            .filter { !REPLACEMENT_DERIVATIVE.containsMatchIn(it.title) }
            .filter { candidate ->
                val title = normaliseTitle(candidate.title)
                title.isNotEmpty() && wantedTitles.any { title.contains(it) }
            }
            .filter { candidate ->
                wantedArtist.isEmpty() ||
                        normaliseArtist(candidate.title).contains(wantedArtist) ||
                        normaliseArtist(candidate.artists).contains(wantedArtist)
            }
            .mapNotNull { candidate ->
                val seconds = parseDurationSeconds(candidate.duration)
                if (wantedSeconds == null || seconds == null) return@mapNotNull null
                val delta = kotlin.math.abs(seconds - wantedSeconds)
                if (delta > REPLACEMENT_MAX_DURATION_DELTA) null else candidate to delta
            }
            .minByOrNull { it.second }
            ?.first
            ?.youtubeId
            ?: return null

        printd("${song.youtubeId} : Matched replacement $best for \"$query\"")
        replacementIds[song.youtubeId] = best
        return best
    }

    /** Letters and digits only, so punctuation and bracketed suffixes stop mattering. */
    private fun normaliseTitle(value: String): String =
        HanziFold.fold(value)
            .lowercase(Locale.ROOT)
            .replace(BRACKET_CHARS, " ")
            .replace(NON_WORD, "")
            .trim()

    /** Same, but keeps only the leading artist so "A / B" still matches "A". */
    private fun normaliseArtist(value: String): String =
        HanziFold.fold(value)
            .lowercase(Locale.ROOT)
            .split(ARTIST_SEPARATOR)
            .firstOrNull()
            .orEmpty()
            .replace(NON_WORD, "")
            .trim()

    private fun parseDurationSeconds(value: String): Int? {
        val parts = value.split(':').map { it.trim().toIntOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /** `simpleText` or the concatenated `runs` of a text node inside this object. */
    private fun JsonObject.plainText(key: String): String? {
        val node = this[key]?.safeObject() ?: return null
        node["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return node["runs"]?.safeArray()
            ?.mapNotNull { it.safeObject()?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
    }

    private fun resolveNewPipeStreamUrl(song: Song): String {
        val service = ServiceList.YouTube
        val extractor = service.getStreamExtractor(song.youtubeUrl)

        extractor.fetchPage()

        val bestAudioStream = extractor.audioStreams
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?: error("No valid audio streams found")

        return bestAudioStream.content
    }

    private suspend fun extractStreamFromRawResponse(
        text: String,
    ): String {
        val root = Json.parseToJsonElement(text).jsonObject

        val newVisitorData = root["responseContext"]
            ?.safeObject()
            ?.get("visitorData")
            ?.jsonPrimitive
            ?.contentOrNull

        if (newVisitorData != null && newVisitorData != visitorData) {
            visitorData = newVisitorData
        }

        val status = root["playabilityStatus"]
            ?.safeObject()
            ?.get("status")
            ?.jsonPrimitive
            ?.contentOrNull

        val reason = root["playabilityStatus"]
            ?.safeObject()
            ?.get("reason")
            ?.jsonPrimitive
            ?.contentOrNull

        val directUrl = root["streamingData"]
            ?.safeObject()
            ?.get("adaptiveFormats")
            ?.safeArray()
            ?.asSequence()
            ?.mapNotNull { it.safeObject() }
            ?.filter {
                it["url"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
            }
            ?.filter {
                it["mimeType"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.startsWith("audio/", ignoreCase = true) == true
            }
            ?.maxByOrNull {
                it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
            }
            ?.get("url")
            ?.jsonPrimitive
            ?.contentOrNull

        if (directUrl == null) {
            // `reason` on its own is ambiguous: "Video unavailable" is what a takedown, a
            // region block and an id that no longer carries a stream all answer. The error
            // screen holds the sub-reason YouTube would draw for the user, which is the only
            // text that separates those cases, so it goes into the log.
            val detail = root["playabilityStatus"]
                ?.safeObject()
                ?.get("errorScreen")
                ?.safeObject()
                ?.get("playerErrorMessageRenderer")
                ?.safeObject()
                ?.let { renderer ->
                    listOfNotNull(
                        renderer.plainText("reason"),
                        renderer.plainText("subreason"),
                    ).joinToString(" / ")
                }
                .orEmpty()

            throw Exception(
                if (detail.isBlank()) "($status) $reason"
                else "($status) $reason — $detail"
            )
        }

        // No HEAD probe here. This URL was signed by the player response we just parsed, so it
        // is valid by construction. The probe used to cost a full round trip on a proxied link
        // (measured 1.8-2.7s of first-byte latency against googlevideo), and it sat in front of
        // the real fetch, so every track start paid it twice.
        return directUrl
    }

    /**
     * True when the URL's own signed `expire` stamp says it stays good for a while.
     *
     * googlevideo URLs carry their expiry in the query string, so reading it locally answers the
     * same question [isYoutubeUrlValid] answers with a HEAD request — without the round trip.
     * On a proxied link that probe measures 1.8-2.7s to first byte, which is exactly the wait
     * felt when a track starts. Anything without a readable stamp falls back to the probe.
     */
    private fun isStreamUrlFresh(url: String): Boolean {
        val expireAt = STREAM_EXPIRE.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000
        return expireAt - nowSeconds > STREAM_EXPIRE_MARGIN_SECONDS
    }

    /** `expire=` in seconds since the epoch, as googlevideo signs it. */
    private val STREAM_EXPIRE = Regex("[?&]expire=(\\d+)")

    /** Re-fetch rather than risk handing the player a URL that dies mid-track. */
    private const val STREAM_EXPIRE_MARGIN_SECONDS = 300L

    private suspend fun isYoutubeUrlValid(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            UmihiHttpClient.client
                .newCall(request)
                .execute()
                .use { response ->
                    response.code in 200..399
                }
        } catch (_: Exception) {
            false
        }
    }
}


enum class SongInfoType(val index: Int) {
    TITLE(0),
    ARTISTS(1),
}
