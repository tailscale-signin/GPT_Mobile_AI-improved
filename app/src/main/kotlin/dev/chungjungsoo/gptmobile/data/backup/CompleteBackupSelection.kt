package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.Serializable

@Serializable
enum class CompleteBackupSection {
    SETTINGS,
    CONVERSATIONS,
    PLATFORMS,
    TOOLS,
    CREDENTIALS,
    LOCAL_MODELS,
    ATTACHMENTS,
    AGENT_HISTORY
}

@Serializable
data class CompleteBackupSelection(
    val sections: Set<CompleteBackupSection> = CompleteBackupSection.entries.toSet()
) {
    fun includes(section: CompleteBackupSection): Boolean = section in sections

    fun normalized(): CompleteBackupSelection {
        val normalized = sections.toMutableSet()
        if (CompleteBackupSection.AGENT_HISTORY in normalized ||
            CompleteBackupSection.ATTACHMENTS in normalized
        ) {
            normalized += CompleteBackupSection.CONVERSATIONS
        }
        return copy(sections = normalized)
    }

    fun toggled(section: CompleteBackupSection, enabled: Boolean): CompleteBackupSelection {
        val updated = if (enabled) sections + section else sections - section
        return copy(sections = updated).normalized()
    }

    companion object {
        val ALL = CompleteBackupSelection()
    }
}
