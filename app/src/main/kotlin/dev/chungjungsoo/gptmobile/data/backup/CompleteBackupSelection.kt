package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.Serializable

@Serializable
enum class CompleteBackupSection {
    SETTINGS,
    CONVERSATIONS,
    PLATFORMS,
    TOOLS,
    CREDENTIALS,
    MEMORY,
    LOCAL_MODELS,
    ATTACHMENTS,
    AGENT_HISTORY,
    STATISTICS,
    AMAZON_DATA
}

@Serializable
data class CompleteBackupSelection(
    val sections: Set<CompleteBackupSection> = DEFAULT_SECTIONS
) {
    val requiresEncryption: Boolean
        get() = includes(CompleteBackupSection.CREDENTIALS) || includes(CompleteBackupSection.MEMORY)

    fun includes(section: CompleteBackupSection): Boolean = section in sections

    fun normalized(): CompleteBackupSelection {
        val normalized = sections.toMutableSet()
        if (CompleteBackupSection.STATISTICS in normalized) normalized += setOf(CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.PLATFORMS)
        if (CompleteBackupSection.AGENT_HISTORY in normalized ||
            CompleteBackupSection.ATTACHMENTS in normalized
        ) {
            normalized += CompleteBackupSection.CONVERSATIONS
        }
        return copy(sections = normalized)
    }

    fun toggled(section: CompleteBackupSection, enabled: Boolean): CompleteBackupSelection {
        val updated = if (enabled) {
            sections + section
        } else {
            sections - section -
                when (section) {
                    CompleteBackupSection.CONVERSATIONS -> setOf(CompleteBackupSection.ATTACHMENTS, CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.STATISTICS)
                    CompleteBackupSection.AGENT_HISTORY -> setOf(CompleteBackupSection.STATISTICS)
                    CompleteBackupSection.PLATFORMS -> setOf(CompleteBackupSection.STATISTICS)
                    else -> emptySet()
                }
        }
        return copy(sections = updated).normalized()
    }

    companion object {
        val DEFAULT_SECTIONS = CompleteBackupSection.entries.toSet() - setOf(
            CompleteBackupSection.CREDENTIALS,
            CompleteBackupSection.MEMORY,
            CompleteBackupSection.TOOLS,
            CompleteBackupSection.LOCAL_MODELS,
            CompleteBackupSection.ATTACHMENTS,
            CompleteBackupSection.AGENT_HISTORY,
            CompleteBackupSection.STATISTICS,
            CompleteBackupSection.AMAZON_DATA
        )
        val ALL = CompleteBackupSelection(CompleteBackupSection.entries.toSet())
    }
}
