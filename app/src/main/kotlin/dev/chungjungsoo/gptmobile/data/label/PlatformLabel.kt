package dev.chungjungsoo.gptmobile.data.label

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.util.UUID

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
     * Parse labels assigned to a platform from its string field.
     */
    fun parseLabels(platformLabelsString: String?): List<String> =
        if (platformLabelsString.isNullOrBlank()) {
            emptyList()
        } else {
            platformLabelsString
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

    /**
     * Format label names into a stored string format.
     */
    fun formatLabels(labels: List<String>): String =
        labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /**
     * Add a label to a platform.
     */
    fun addLabelToPlatform(platform: PlatformV2, labelName: String): PlatformV2 {
        val currentLabels = parseLabels(platform.labels)
        if (currentLabels.any { it.equals(labelName.trim(), ignoreCase = true) }) {
            return platform
        }
        val updated = currentLabels + labelName.trim()
        return platform.copy(labels = formatLabels(updated))
    }

    /**
     * Remove a label from a platform.
     */
    fun removeLabelFromPlatform(platform: PlatformV2, labelName: String): PlatformV2 {
        val currentLabels = parseLabels(platform.labels)
        val updated = currentLabels.filterNot { it.equals(labelName.trim(), ignoreCase = true) }
        return platform.copy(labels = if (updated.isEmpty()) null else formatLabels(updated))
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
