package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale

/** Spelling dictionaries, not claims that a synthesis model can change its trained accent. */
internal object VoicePronunciationLocales {
    private val regional = mapOf(
        "fr" to listOf("fr" to "French · shared", "fr-FR" to "French · France", "fr-CA" to "French · Canada"),
        "pt" to listOf("pt" to "Portuguese · shared", "pt-BR" to "Portuguese · Brazil", "pt-PT" to "Portuguese · Portugal", "pt-PT-azores" to "Portuguese · Azores")
    )

    fun base(tag: String): String = Locale.forLanguageTag(tag).language

    fun choices(tag: String): List<Pair<String, String>> = regional[base(tag)] ?: listOf(base(tag) to Locale.forLanguageTag(tag).displayName)

    fun valid(tag: String): Boolean = tag == base(tag) && tag in setOf("en", "es", "it", "hi", "zh", "ja", "de", "nl") || regional.values.any { choices -> choices.any { it.first == tag } }

    fun selected(language: String, selections: Map<String, String>): String {
        val base = base(language)
        return selections[base]?.takeIf { candidate -> choices(language).any { it.first == candidate } } ?: base
    }
}
