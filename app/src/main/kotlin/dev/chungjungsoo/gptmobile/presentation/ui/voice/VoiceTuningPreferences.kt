package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceListeningStyle
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationLocales
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationOverrides
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationTransfer
import org.json.JSONObject

internal data class VoiceTuningPreferences(
    val listeningStyle: VoiceListeningStyle = VoiceListeningStyle.NATURAL,
    val captureBatchMs: Int = 40,
    val handsFreeInterruption: Boolean = false,
    val pronunciation: Map<String, String> = emptyMap(),
    val captionOffsetMs: Int = 0,
    val localizedPronunciations: Map<String, Map<String, String>> = emptyMap(),
    val spokenAcknowledgments: Boolean = true,
    val pronunciationLocales: Map<String, String> = emptyMap()
) {
    fun pronunciationLanguage(language: String) = VoicePronunciationLocales.selected(language, pronunciationLocales)

    /** Existing shared entries remain effective when a more specific regional dictionary is selected. */
    fun dictionary(language: String): Map<String, String> {
        val base = VoicePronunciationLocales.base(language)
        val shared = if (base == "en") pronunciation else localizedPronunciations[base].orEmpty()
        val selected = pronunciationLanguage(language)
        return if (selected == base) {
            shared
        } else {
            val regional = localizedPronunciations[selected].orEmpty()
            val replaced = regional.keys.mapTo(mutableSetOf(), VoicePronunciationOverrides::key)
            shared.filterKeys { VoicePronunciationOverrides.key(it) !in replaced } + regional
        }
    }

    fun editableDictionary(language: String): Map<String, String> = pronunciationLanguage(language).let {
        if (it == "en") pronunciation else localizedPronunciations[it].orEmpty()
    }

    fun inheritedDictionary(language: String): Map<String, String> {
        val base = VoicePronunciationLocales.base(language)
        return if (pronunciationLanguage(language) == base) emptyMap() else localizedPronunciations[base].orEmpty()
    }

    fun withDictionary(language: String, entries: Map<String, String>): VoiceTuningPreferences {
        val selected = pronunciationLanguage(language)
        return when {
            selected == "en" -> copy(pronunciation = entries)
            entries.isEmpty() -> copy(localizedPronunciations = localizedPronunciations - selected)
            else -> copy(localizedPronunciations = localizedPronunciations + (selected to entries))
        }
    }
}

internal fun readVoiceTuningPreferences(saved: Map<String, *>): VoiceTuningPreferences {
    fun entries(json: JSONObject?) = buildMap {
        val seen = mutableSetOf<String>()
        json?.keys()?.asSequence()?.take(VoicePronunciationOverrides.MAX_ENTRIES)?.forEach { key ->
            val value = json.opt(key) as? String
            if (value != null && validPronunciation(key, value) && seen.add(VoicePronunciationOverrides.key(key))) put(key, value)
        }
    }
    fun read(key: String, limit: Int): JSONObject? = (saved[key] as? String)?.takeIf { it.length <= limit }?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    val pronunciation = entries(read("pronunciation_overrides", VoicePronunciationOverrides.MAX_EDITOR_CHARS))
    val localized = read("localized_pronunciations", 2_000_000)
    val locales = read("pronunciation_locales", 2_048)
    return VoiceTuningPreferences(
        listeningStyle = VoiceListeningStyle.entries.firstOrNull { it.name == saved["listening_style"] } ?: VoiceListeningStyle.NATURAL,
        captureBatchMs = (saved["capture_batch_ms"] as? Int)?.takeIf { it in setOf(20, 40, 80, 100) } ?: 40,
        handsFreeInterruption = saved["hands_free_interruption"] as? Boolean ?: false,
        pronunciation = pronunciation,
        spokenAcknowledgments = saved["spoken_acknowledgments"] as? Boolean ?: true,
        localizedPronunciations = buildMap {
            (SpeechLanguages.synthesis.map { it.dictionaryLanguage } + listOf("de", "nl", "fr-FR", "fr-CA", "pt-BR", "pt-PT", "pt-PT-azores")).distinct().filter { it != "en" }.forEach { language ->
                entries(localized?.optJSONObject(language)).takeIf { it.isNotEmpty() }?.let { put(language, it) }
            }
        },
        pronunciationLocales = buildMap {
            for (base in listOf("fr", "pt")) {
                val selected = locales?.opt(base) as? String
                if (selected != null && VoicePronunciationLocales.choices(base).any { it.first == selected }) put(base, selected)
            }
        },
        captionOffsetMs = (saved["caption_offset_ms"] as? Int)?.coerceIn(-500, 500) ?: 0
    )
}

internal fun validPronunciation(word: String, replacement: String): Boolean = VoicePronunciationOverrides.valid(word, replacement)

internal fun parsePronunciationEditor(text: String): Map<String, String>? = VoicePronunciationTransfer.parseEntries(text)
