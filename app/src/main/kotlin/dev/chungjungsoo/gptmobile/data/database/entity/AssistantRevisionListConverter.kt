package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class AssistantRevisionListConverter {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @TypeConverter
    fun fromString(value: String): List<AssistantRevision> {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "[]") {
            return emptyList()
        }
        return try {
            json.decodeFromString(trimmed)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(value: List<AssistantRevision>): String {
        if (value.isEmpty()) {
            return "[]"
        }
        return json.encodeToString(value)
    }
}
