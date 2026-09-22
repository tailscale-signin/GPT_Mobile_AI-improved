package dev.melo.gptmobile.improved.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entity representing a shared platform label supporting cross-platform usage.
 */
@Entity(tableName = "platform_labels")
data class PlatformLabel(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorHex: String,
    val format: String = "hex", // hex, rgb, rgba
    val createdAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0,
    val isPredefined: Boolean = false
) {
    companion object {
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 50

        // 12-color predefined palette
        val PREDEFINED_PALETTE = listOf(
            PlatformLabel("red", "#FF6B6B", "hex", isPredefined = true),
            PlatformLabel("orange", "#FFA94D", "hex", isPredefined = true),
            PlatformLabel("yellow", "#FDCB6E", "hex", isPredefined = true),
            PlatformLabel("green", "#6BCB77", "hex", isPredefined = true),
            PlatformLabel("teal", "#4D96FF", "hex", isPredefined = true),
            PlatformLabel("blue", "#6BCBFF", "hex", isPredefined = true),
            PlatformLabel("indigo", "#6B63FF", "hex", isPredefined = true),
            PlatformLabel("purple", "#C471F5", "hex", isPredefined = true),
            PlatformLabel("pink", "#F64F59", "hex", isPredefined = true),
            PlatformLabel("brown", "#A56E5D", "hex", isPredefined = true),
            PlatformLabel("gray", "#8D99AE", "hex", isPredefined = true),
            PlatformLabel("black", "#2D3436", "hex", isPredefined = true)
        )

        fun validateColor(color: String): Result<String> {
            return try {
                // Remove # if present
                val hex = color.removePrefix("#")
                if (hex.length != 6 && hex.length != 8) {
                    return Result.failure(IllegalArgumentException("Color must be 6 or 8 hex digits"))
                }
                // Validate each character is a valid hex digit
                hex.forEach { c ->
                    if (!c.isDigit() && !c in 'a'..'f' && !c in 'A'..'F') {
                        return Result.failure(IllegalArgumentException("Invalid hex character: $c"))
                    }
                }
                Result.success(color)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        fun validateName(name: String): Result<String> {
            return try {
                if (name.length < MIN_LENGTH || name.length > MAX_LENGTH) {
                    return Result.failure(
                        IllegalArgumentException("Name must be $MIN_LENGTH-$MAX_LENGTH characters")
                    )
                }
                Result.success(name)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

/**
 * Manager for cross-platform label operations.
 */
class PlatformLabelManager @Inject constructor(
    private val platformLabelDao: PlatformLabelDao
) {
    /**
     * Get all predefined palette labels.
     */
    suspend fun getPredefinedPalette(): List<PlatformLabel> =
        PlatformLabel.PREDEFINED_PALETTE

    /**
     * Create a new custom label with validation.
     */
    suspend fun createLabel(name: String, colorHex: String): Result<PlatformLabel> {
        return try {
            val validatedName = PlatformLabel.validateName(name)
                .fold({ it }, { throw it })
            val validatedColor = PlatformLabel.validateColor(colorHex)
                .fold({ it }, { throw it })

            val label = PlatformLabel(
                name = validatedName,
                colorHex = validatedColor,
                isPredefined = false
            )
            platformLabelDao.insert(label)
            Result.success(label)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update label usage count.
     */
    suspend fun incrementUsageCount(labelId: String) {
        platformLabelDao.incrementUsageCount(labelId)
    }

    /**
     * Delete a label.
     */
    suspend fun deleteLabel(labelId: String) {
        platformLabelDao.delete(labelId)
    }

    /**
     * Get all labels (predefined + custom).
     */
    suspend fun getAllLabels(): List<PlatformLabel> =
        platformLabelDao.getAllLabels()

    /**
     * Get a specific label by ID.
     */
    suspend fun getLabelById(labelId: String): PlatformLabel? =
        platformLabelDao.getLabelById(labelId)
}