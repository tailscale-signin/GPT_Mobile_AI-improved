package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale

/** Bounded spoken-English substitutions; pronunciation never enters diagnostic output. */
internal object VoicePronunciationOverrides {
    private val word = Regex("[a-zA-Z]+(?:'[a-zA-Z]+)*")
    private val spoken = Regex("[a-zA-Z]+(?:'[a-zA-Z]+)*(?: [a-zA-Z]+(?:'[a-zA-Z]+)*)*")

    fun valid(original: String, pronunciation: String): Boolean =
        original.length in 1..48 &&
            word.matches(original) &&
            pronunciation.length in 1..96 &&
            spoken.matches(pronunciation) &&
            pronunciation.count { it == ' ' } < 8

    fun clean(overrides: Map<String, String>): Map<String, String> = overrides.entries.take(128).mapNotNull { (original, pronunciation) ->
        val key = original.trim()
        val value = pronunciation.trim().replace(Regex("\\s+"), " ")
        if (valid(key, value)) key.lowercase(Locale.ROOT) to value.lowercase(Locale.ROOT) else null
    }.toMap()
}
