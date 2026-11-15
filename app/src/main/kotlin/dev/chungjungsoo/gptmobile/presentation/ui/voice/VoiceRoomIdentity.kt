package dev.chungjungsoo.gptmobile.presentation.ui.voice

/** Room IDs can be reused after clearing or restoring the database; creation time fences reuse. */
data class VoiceRoomIdentity(val id: Int, val createdAt: Long) {
    val preferenceKey: String get() = "voice_room_${id}_$createdAt"

    companion object {
        fun persistent(id: Int, createdAt: Long, temporary: Boolean): VoiceRoomIdentity? =
            if (!temporary && id > 0 && createdAt > 0) VoiceRoomIdentity(id, createdAt) else null

        fun fromPreference(key: String, value: Any?): VoiceRoomIdentity? {
            if (value != true || !key.startsWith("voice_room_")) return null
            val parts = key.removePrefix("voice_room_").split('_')
            if (parts.size != 2) return null
            return persistent(parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, temporary = false)
        }
    }
}
