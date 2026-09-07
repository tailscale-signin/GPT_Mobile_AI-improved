package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StringListConverter {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @TypeConverter
    fun fromString(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        return try {
            json.decodeFromString<List<String>>(trimmed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        if (list.isEmpty()) return "[]"
        return json.encodeToString(list)
    }
}
