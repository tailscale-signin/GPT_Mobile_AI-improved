package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceListeningStyle
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationOverrides
import org.json.JSONObject

internal data class VoiceTuningPreferences(
    val listeningStyle: VoiceListeningStyle = VoiceListeningStyle.NATURAL,
    val captureBatchMs: Int = 40,
    val handsFreeInterruption: Boolean = false,
    val pronunciation: Map<String, String> = emptyMap(),
    val captionOffsetMs: Int = 0,
    val localizedPronunciations: Map<String, Map<String, String>> = emptyMap()
) {
    fun dictionary(language: String) = if (language == "en") pronunciation else localizedPronunciations[language].orEmpty()

    fun withDictionary(language: String, entries: Map<String, String>) = when {
        language == "en" -> copy(pronunciation = entries)
        entries.isEmpty() -> copy(localizedPronunciations = localizedPronunciations - language)
        else -> copy(localizedPronunciations = localizedPronunciations + (language to entries))
    }
}

internal fun readVoiceTuningPreferences(saved: Map<String, *>): VoiceTuningPreferences {
    fun entries(json: JSONObject?) = buildMap {
        json?.keys()?.asSequence()?.take(100)?.forEach { key ->
            val value = json.opt(key) as? String
            if (value != null && validPronunciation(key, value)) put(key, value)
        }
    }
    fun read(key: String, limit: Int): JSONObject? = (saved[key] as? String)?.takeIf { it.length <= limit }?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    val pronunciation = entries(read("pronunciation_overrides", 32_000))
    val localized = read("localized_pronunciations", 256_000)
    return VoiceTuningPreferences(
        listeningStyle = VoiceListeningStyle.entries.firstOrNull { it.name == saved["listening_style"] } ?: VoiceListeningStyle.NATURAL,
        captureBatchMs = (saved["capture_batch_ms"] as? Int)?.takeIf { it in setOf(20, 40, 80, 100) } ?: 40,
        handsFreeInterruption = saved["hands_free_interruption"] as? Boolean ?: false,
        pronunciation = pronunciation,
        localizedPronunciations = buildMap {
            SpeechLanguages.synthesis.map { it.dictionaryLanguage }.distinct().filter { it != "en" }.forEach { language ->
                entries(localized?.optJSONObject(language)).takeIf { it.isNotEmpty() }?.let { put(language, it) }
            }
        },
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
