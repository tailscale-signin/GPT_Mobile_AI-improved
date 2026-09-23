package dev.chungjungsoo.gptmobile.llama

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Keeps older settings compatible with newly added fields at every read site. */
object AdvancedSettingsJson {
    private val gson = Gson()

    fun decode(raw: String?): AdvancedSettings {
        if (raw.isNullOrBlank()) return AdvancedSettings()

        return runCatching {
            val merged = gson.toJsonTree(AdvancedSettings()).asJsonObject
            val saved = JsonParser.parseString(raw).asJsonObject
            saved.entrySet().forEach { (name, value) ->
                // Explicit false and zero are user choices; only absent/null fields use defaults.
                if (merged.has(name) && !value.isJsonNull) {
                    merged.add(name, value)
                }
            }
            gson.fromJson(merged, AdvancedSettings::class.java)
        }.getOrElse { AdvancedSettings() }
    }

    fun encode(settings: AdvancedSettings): String = gson.toJson(settings)
}
