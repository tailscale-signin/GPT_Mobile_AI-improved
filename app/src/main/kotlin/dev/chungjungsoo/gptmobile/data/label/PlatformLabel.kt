package dev.chungjungsoo.gptmobile.data.label

import androidx.compose.ui.graphics.Color
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.util.UUID
import kotlin.math.abs

/**
 * Entity representing a shared label that can be assigned across multiple AI platforms.
 */
data class PlatformLabel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorHex: String = "#4CAF50",
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Parsed representation of a label with optional custom hex color.
 */
data class DisplayLabel(
    val name: String,
    val colorHex: String? = null
)

/**
 * Standard predefined color palette for labels (12 colors).
 */
val PREDEFINED_LABEL_COLORS = listOf(
    "#F44336", // Red
    "#E91E63", // Pink
    "#9C27B0", // Purple
    "#673AB7", // Deep Purple
    "#3F51B5", // Indigo
    "#2196F3", // Blue
    "#03A9F4", // Light Blue
    "#009688", // Teal
    "#4CAF50", // Green
    "#8BC34A", // Light Green
    "#FF9800", // Orange
    "#795548"  // Brown
)

private val HEX_COLOR_REGEX = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")

/**
 * Derives consistent fallback color-coded palette for label badges if hex is absent.
 */
fun getBeveledLabelColors(label: String): Triple<Color, Color, Color> {
    val hash = abs(label.hashCode())
    val palette = listOf(
        Triple(Color(0x2A1976D2), Color(0xFF1976D2), Color(0xFF64B5F6)), // Blue
        Triple(Color(0x2A388E3C), Color(0xFF388E3C), Color(0xFF81C784)), // Green
        Triple(Color(0x2A7B1FA2), Color(0xFF7B1FA2), Color(0xFFBA68C8)), // Purple
        Triple(Color(0x2AE65100), Color(0xFFE65100), Color(0xFFFFB74D)), // Orange
        Triple(Color(0x2A00838F), Color(0xFF00838F), Color(0xFF4DD0E1)), // Cyan
        Triple(Color(0x2AC2185B), Color(0xFFC2185B), Color(0xFFF06292)), // Pink
        Triple(Color(0x2A5D4037), Color(0xFF5D4037), Color(0xFFA1887F))  // Brown
    )
    return palette[hash % palette.size]
}

/**
 * Resolves colors (container, border, text) for a given DisplayLabel.
 */
fun resolveDisplayLabelColors(label: DisplayLabel): Triple<Color, Color, Color> {
    val parsedColor = label.colorHex?.let { hex ->
        runCatching {
            val colorInt = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
            Color(colorInt)
        }.getOrNull()
    }
    return if (parsedColor != null) {
        Triple(
            parsedColor.copy(alpha = 0.20f),
            parsedColor.copy(alpha = 0.85f),
            parsedColor
        )
    } else {
        getBeveledLabelColors(label.name)
    }
}

/**
 * Utility functions for parsing and serializing labels on PlatformV2.
 * Platform labels can be stored as comma-separated strings with optional '#HEX' suffix:
 * e.g. "Work#2196F3,Personal#4CAF50,Fast"
 */
object PlatformLabelManager {

    /**
     * Validates label input fields.
     */
    fun validate(name: String, colorHex: String, description: String?): String? {
        val trimmed = name.trim()
        if (trimmed.length < 2 || trimmed.length > 50) {
            return "Label name must be between 2 and 50 characters."
        }
        if (!HEX_COLOR_REGEX.matches(colorHex.trim())) {
            return "Invalid hex color format. Use #RRGGBB or #RGB."
        }
        if (description != null && description.length > 200) {
            return "Description cannot exceed 200 characters."
        }
        return null
    }

    /**
     * Parse raw string field into typed DisplayLabel objects.
     */
    fun parseDisplayLabels(platformLabelsString: String?): List<DisplayLabel> {
        val raw = platformLabelsString?.trim()
        if (raw.isNullOrBlank()) return emptyList()

        val tokens = if (raw.startsWith("[") && raw.endsWith("]")) {
            raw.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } else {
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }

        return tokens.map { token ->
            if (token.contains("#")) {
                val idx = token.indexOf('#')
                val name = token.substring(0, idx).trim()
                val hexPart = token.substring(idx).trim()
                val validHex = if (HEX_COLOR_REGEX.matches(hexPart)) hexPart else null
                DisplayLabel(name = name, colorHex = validHex)
            } else {
                DisplayLabel(name = token, colorHex = null)
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * Parse labels assigned to a platform into raw string names.
     */
    fun parseLabels(platformLabelsString: String?): List<String> =
        parseDisplayLabels(platformLabelsString).map { it.name }.distinct()

    /**
     * Format DisplayLabels list into stored string format.
     */
    fun formatDisplayLabels(labels: List<DisplayLabel>): String =
        labels.distinctBy { it.name.lowercase() }
            .map { label ->
                if (!label.colorHex.isNullOrBlank() && HEX_COLOR_REGEX.matches(label.colorHex)) {
                    "${label.name.trim()}#${label.colorHex.trim().removePrefix("#")}"
                } else {
                    label.name.trim()
                }
            }
            .filter { it.isNotBlank() }
            .joinToString(",")

    /**
     * Format label names into a stored string format.
     */
    fun formatLabels(labels: List<String>): String =
        labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /**
     * Extract all unique DisplayLabels discovered across a collection of platforms.
     */
    fun extractSharedLabels(platforms: List<PlatformV2>): List<DisplayLabel> {
        val map = linkedMapOf<String, DisplayLabel>()
        for (platform in platforms) {
            for (displayLabel in parseDisplayLabels(platform.labels)) {
                val key = displayLabel.name.lowercase()
                if (!map.containsKey(key) || (map[key]?.colorHex == null && displayLabel.colorHex != null)) {
                    map[key] = displayLabel
                }
            }
        }
        return map.values.toList()
    }

    /**
     * Add a label to a platform.
     */
    fun addLabelToPlatform(platform: PlatformV2, labelName: String, colorHex: String? = null): PlatformV2 {
        val current = parseDisplayLabels(platform.labels).toMutableList()
        val existingIndex = current.indexOfFirst { it.name.equals(labelName.trim(), ignoreCase = true) }
        if (existingIndex >= 0) {
            if (colorHex != null) {
                current[existingIndex] = current[existingIndex].copy(colorHex = colorHex)
            }
        } else {
            current.add(DisplayLabel(name = labelName.trim(), colorHex = colorHex))
        }
        val formatted = formatDisplayLabels(current)
        return platform.copy(labels = formatted.ifBlank { null })
    }

    /**
     * Remove a label from a platform.
     */
    fun removeLabelFromPlatform(platform: PlatformV2, labelName: String): PlatformV2 {
        val current = parseDisplayLabels(platform.labels)
        val updated = current.filterNot { it.name.equals(labelName.trim(), ignoreCase = true) }
        val formatted = formatDisplayLabels(updated)
        return platform.copy(labels = formatted.ifBlank { null })
    }

    /**
     * Calculate usage counts for each label across a list of platforms.
     */
    fun calculateUsageCounts(platforms: List<PlatformV2>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        platforms.forEach { platform ->
            parseLabels(platform.labels).forEach { label ->
                counts[label] = (counts[label] ?: 0) + 1
            }
        }
        return counts
    }
}
