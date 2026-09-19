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
 * Lightweight label representation containing name and optional custom color hex.
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
    "#795548" // Brown
)

private val HEX_COLOR_REGEX = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")

/**
 * Utility functions for parsing and serializing labels on PlatformV2.
 * Platform labels can be stored as JSON or comma-separated strings.
 * Labels can optionally include hex color: "LabelName#HexColor"
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
     * Parse labels assigned to a platform from its string field into DisplayLabel objects.
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
                val parts = token.split("#", limit = 2)
                val name = parts[0].trim()
                val hex = "#" + parts[1].trim().removePrefix("#")
                DisplayLabel(name = name, colorHex = hex)
            } else {
                DisplayLabel(name = token, colorHex = null)
            }
        }.distinctBy { it.name.lowercase() }
    }

    /**
     * Format a list of DisplayLabels into stored string format.
     */
    fun formatDisplayLabels(labels: List<DisplayLabel>): String {
        return labels
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase() }
            .joinToString(",") { label ->
                if (!label.colorHex.isNullOrBlank()) {
                    "${label.name.trim()}#${label.colorHex.trim().removePrefix("#")}"
                } else {
                    label.name.trim()
                }
            }
    }

    /**
     * Parse label names assigned to a platform from its string field.
     */
    fun parseLabels(platformLabelsString: String?): List<String> =
        parseDisplayLabels(platformLabelsString).map { it.name }

    /**
     * Format label names into a stored string format.
     */
    fun formatLabels(labels: List<String>): String =
        labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /**
     * Add a label to a platform.
     */
    fun addLabelToPlatform(platform: PlatformV2, labelName: String, colorHex: String? = null): PlatformV2 {
        val currentLabels = parseDisplayLabels(platform.labels).toMutableList()
        val existingIndex = currentLabels.indexOfFirst { it.name.equals(labelName.trim(), ignoreCase = true) }
        if (existingIndex >= 0) {
            if (colorHex != null) {
                currentLabels[existingIndex] = currentLabels[existingIndex].copy(colorHex = colorHex)
            }
        } else {
            currentLabels.add(DisplayLabel(name = labelName.trim(), colorHex = colorHex))
        }
        val formatted = formatDisplayLabels(currentLabels)
        return platform.copy(labels = if (formatted.isBlank()) null else formatted)
    }

    /**
     * Remove a label from a platform.
     */
    fun removeLabelFromPlatform(platform: PlatformV2, labelName: String): PlatformV2 {
        val currentLabels = parseDisplayLabels(platform.labels)
        val updated = currentLabels.filterNot { it.name.equals(labelName.trim(), ignoreCase = true) }
        val formatted = formatDisplayLabels(updated)
        return platform.copy(labels = if (formatted.isBlank()) null else formatted)
    }

    /**
     * Extracts all unique labels and their latest assigned colors across all platforms.
     */
    fun extractSharedLabels(platforms: List<PlatformV2>): List<DisplayLabel> {
        val labelMap = mutableMapOf<String, DisplayLabel>()
        platforms.forEach { platform ->
            parseDisplayLabels(platform.labels).forEach { label ->
                val key = label.name.lowercase()
                if (!labelMap.containsKey(key) || (labelMap[key]?.colorHex == null && label.colorHex != null)) {
                    labelMap[key] = label
                }
            }
        }
        return labelMap.values.sortedBy { it.name.lowercase() }
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

/**
 * Resolves colors for a DisplayLabel. Uses custom hex if valid, otherwise falls back to deterministic palette.
 * Returns Triple(containerColor, borderColor, textColor).
 */
fun resolveDisplayLabelColors(label: DisplayLabel): Triple<Color, Color, Color> {
    val customColor = label.colorHex?.let { hex ->
        runCatching {
            val cleanHex = hex.trim().removePrefix("#")
            val colorInt = if (cleanHex.length == 6) {
                android.graphics.Color.parseColor("#$cleanHex")
            } else if (cleanHex.length == 3) {
                val expanded = "${cleanHex[0]}${cleanHex[0]}${cleanHex[1]}${cleanHex[1]}${cleanHex[2]}${cleanHex[2]}"
                android.graphics.Color.parseColor("#$expanded")
            } else {
                null
            }
            colorInt?.let { Color(it) }
        }.getOrNull()
    }

    if (customColor != null) {
        val container = customColor.copy(alpha = 0.20f)
        val border = customColor.copy(alpha = 0.85f)
        val text = customColor
        return Triple(container, border, text)
    }

    val hash = abs(label.name.hashCode())
    val palette = listOf(
        Triple(Color(0x2A1976D2), Color(0xFF1976D2), Color(0xFF64B5F6)), // Blue
        Triple(Color(0x2A388E3C), Color(0xFF388E3C), Color(0xFF81C784)), // Green
        Triple(Color(0x2A7B1FA2), Color(0xFF7B1FA2), Color(0xFFBA68C8)), // Purple
        Triple(Color(0x2AE65100), Color(0xFFE65100), Color(0xFFFFB74D)), // Orange
        Triple(Color(0x2A00838F), Color(0xFF00838F), Color(0xFF4DD0E1)), // Cyan
        Triple(Color(0x2AC2185B), Color(0xFFC2185B), Color(0xFFF06292)), // Pink
        Triple(Color(0x2A5D4037), Color(0xFF5D4037), Color(0xFFA1887F)) // Brown
    )
    return palette[hash % palette.size]
}
