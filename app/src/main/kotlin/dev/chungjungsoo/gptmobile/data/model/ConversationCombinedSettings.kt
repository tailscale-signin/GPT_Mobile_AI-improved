package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Conversation preferences, independent of tool grants and provider capacity. */
@Serializable(with = ConversationCombinedSettingsSerializer::class)
data class ConversationCombinedSettings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val collectionBias: Int = 25,
    val effort: Int = 50,
    val revision: Long = 0,
    val creativity: Float? = null,
    val preservedFields: JsonObject = JsonObject(emptyMap())
) {
    val isSupported: Boolean get() = schemaVersion in 0..CURRENT_SCHEMA

    fun normalized(): ConversationCombinedSettings = if (!isSupported) {
        this
    } else {
        copy(
            schemaVersion = CURRENT_SCHEMA,
            collectionBias = nearestStop(collectionBias),
            effort = nearestStop(effort),
            revision = revision.coerceAtLeast(0),
            creativity = creativity?.takeIf(Float::isFinite)?.coerceIn(0f, 2f)
        )
    }

    companion object {
        const val CURRENT_SCHEMA = 1
        val STOPS = listOf(0, 25, 50, 75, 100)

        fun nearestStop(value: Int): Int = STOPS.minBy { kotlin.math.abs(it - value.coerceIn(0, 100)) }
    }
}

/** Future settings remain round-trippable, including incompatible field shapes. */
object ConversationCombinedSettingsSerializer : KSerializer<ConversationCombinedSettings> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ConversationCombinedSettings")

    override fun deserialize(decoder: Decoder): ConversationCombinedSettings {
        val objectValue = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject
            ?: error("Combined settings must be a JSON object")
        val version = (objectValue["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1
        return ConversationCombinedSettings(
            schemaVersion = version,
            collectionBias = (objectValue["collectionBias"] as? JsonPrimitive)?.intOrNull ?: 25,
            effort = (objectValue["effort"] as? JsonPrimitive)?.intOrNull ?: 50,
            revision = (objectValue["revision"] as? JsonPrimitive)?.longOrNull ?: 0,
            creativity = (objectValue["creativity"] as? JsonPrimitive)?.floatOrNull,
            preservedFields = if (version in 0..ConversationCombinedSettings.CURRENT_SCHEMA) JsonObject(objectValue.filterKeys { it !in setOf("schemaVersion", "collectionBias", "effort", "revision", "creativity") }) else objectValue
        ).normalized()
    }

    override fun serialize(encoder: Encoder, value: ConversationCombinedSettings) {
        val normalized = value.normalized()
        val payload = if (!normalized.isSupported && normalized.preservedFields.isNotEmpty()) {
            normalized.preservedFields
        } else {
            JsonObject(
                normalized.preservedFields + mapOf(
                    "schemaVersion" to JsonPrimitive(normalized.schemaVersion),
                    "collectionBias" to JsonPrimitive(normalized.collectionBias),
                    "effort" to JsonPrimitive(normalized.effort),
                    "revision" to JsonPrimitive(normalized.revision),
                    "creativity" to (normalized.creativity?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                )
            )
        }
        (encoder as JsonEncoder).encodeJsonElement(payload)
    }
}
