package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoiceListeningStyle
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationOverrides
import org.json.JSONObject

internal data class VoiceTuningPreferences(
    val listeningStyle: VoiceListeningStyle = VoiceListeningStyle.NATURAL,
    val captureBatchMs: Int = 40,
    val handsFreeInterruption: Boolean = false,
    val pronunciation: Map<String, String> = emptyMap(),
    val captionOffsetMs: Int = 0
)

internal fun readVoiceTuningPreferences(saved: Map<String, *>): VoiceTuningPreferences {
    val pronunciation = runCatching {
        val raw = (saved["pronunciation_overrides"] as? String)?.takeIf { it.length <= 32_000 } ?: "{}"
        val json = JSONObject(raw)
        buildMap {
            json.keys().asSequence().take(100).forEach { key ->
                val value = json.optString(key)
                if (validPronunciation(key, value)) put(key, value)
            }
        }
    }.getOrDefault(emptyMap())
    return VoiceTuningPreferences(
        listeningStyle = VoiceListeningStyle.entries.firstOrNull { it.name == saved["listening_style"] } ?: VoiceListeningStyle.NATURAL,
        captureBatchMs = (saved["capture_batch_ms"] as? Int)?.takeIf { it in setOf(20, 40, 80, 100) } ?: 40,
        handsFreeInterruption = saved["hands_free_interruption"] as? Boolean ?: false,
        pronunciation = pronunciation,
        captionOffsetMs = (saved["caption_offset_ms"] as? Int)?.coerceIn(-500, 500) ?: 0
    )
}

internal fun validPronunciation(word: String, replacement: String): Boolean = VoicePronunciationOverrides.valid(word, replacement)

internal fun parsePronunciationEditor(text: String): Map<String, String>? {
    val lines = text.lines().filter(String::isNotBlank)
    if (lines.size > 100) return null
    val result = linkedMapOf<String, String>()
    val seen = mutableSetOf<String>()
    for (line in lines) {
        val separator = line.indexOf('=')
        if (separator < 1) return null
        val word = line.substring(0, separator).trim()
        val replacement = line.substring(separator + 1).trim()
        if (!validPronunciation(word, replacement) || !seen.add(VoicePronunciationOverrides.key(word))) return null
        result[word] = replacement
    }
    return result
}
