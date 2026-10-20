package ca.ilianokokoro.umihi.music.models.lyrics.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Plain readers over [JsonElement] instead of `@Serializable` classes.
 *
 * These three services reshape their responses without warning (a missing `artists` array, a
 * `duration` that is a string on one endpoint and a number on the next). A generated schema turns
 * every such change into a thrown `MissingFieldException`; walking the tree turns it into a null
 * for that one field, which is exactly the failure mode a lyrics lookup should have.
 */
internal fun JsonElement?.obj(name: String): JsonElement? = (this as? JsonObject)?.get(name)

internal fun JsonElement?.str(name: String): String? =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.long(name: String): Long? = str(name)?.toLongOrNull()

internal fun JsonElement?.list(name: String): List<JsonElement> =
    ((this as? JsonObject)?.get(name) as? JsonArray)?.toList().orEmpty()
