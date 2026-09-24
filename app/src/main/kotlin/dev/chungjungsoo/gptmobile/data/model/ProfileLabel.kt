package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ProfileLabel(
    val name: String,
    val colorHex: String? = null
) {
    val key: String get() = name.trim().lowercase()
}

val PROFILE_LABEL_COLOR_PRESETS: List<String> = listOf(
    "1976D2",
    "388E3C",
    "7B1FA2",
    "E65100",
    "00838F",
    "C2185B",
    "C62828",
    "00695C",
    "F9A825",
    "455A64"
)

private val labelJson = Json { ignoreUnknownKeys = true }

fun parseProfileLabels(raw: String?): List<ProfileLabel> {
    if (raw.isNullOrBlank()) return emptyList()
    val values = if (raw.trim().startsWith("[") && raw.trim().endsWith("]")) {
        runCatching { labelJson.decodeFromString<List<String>>(raw) }
            .getOrElse {
                raw.trim().removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
            }
    } else {
        raw.split(",")
    }
    return values.mapNotNull(::parseProfileLabelToken)
        .distinctBy(ProfileLabel::key)
}

fun encodeProfileLabels(labels: List<ProfileLabel>): String? {
    val normalized = labels
        .mapNotNull { label ->
            val name = label.name.trim()
            if (name.isBlank()) null else ProfileLabel(name, normalizeLabelColor(label.colorHex))
        }
        .distinctBy(ProfileLabel::key)
    if (normalized.isEmpty()) return null
    return labelJson.encodeToString(
        normalized.map { label ->
            label.colorHex?.let { "${label.name}#$it" } ?: label.name
        }
    )
}

fun collectReusableProfileLabels(rawLabels: Iterable<String?>): List<ProfileLabel> {
    val byName = linkedMapOf<String, ProfileLabel>()
    rawLabels.forEach { raw ->
        parseProfileLabels(raw).forEach { label ->
            val existing = byName[label.key]
            if (existing == null || (existing.colorHex == null && label.colorHex != null)) {
                byName[label.key] = label
            }
        }
    }
    return byName.values.sortedBy { it.name.lowercase() }
}

fun normalizeLabelColor(value: String?): String? {
    val cleaned = value?.trim()?.removePrefix("#")?.uppercase().orEmpty()
    return cleaned.takeIf {
        (it.length == 6 || it.length == 8) && it.all { char -> char in '0'..'9' || char in 'A'..'F' }
    }
}

private fun parseProfileLabelToken(token: String): ProfileLabel? {
    val cleaned = token.trim().removeSurrounding("\"")
    if (cleaned.isBlank()) return null
    val hashIndex = cleaned.lastIndexOf('#')
    if (hashIndex <= 0 || hashIndex == cleaned.lastIndex) {
        return ProfileLabel(cleaned)
    }
    val possibleColor = normalizeLabelColor(cleaned.substring(hashIndex + 1))
    return if (possibleColor == null) {
        ProfileLabel(cleaned)
    } else {
        ProfileLabel(cleaned.substring(0, hashIndex).trim(), possibleColor)
    }
}
