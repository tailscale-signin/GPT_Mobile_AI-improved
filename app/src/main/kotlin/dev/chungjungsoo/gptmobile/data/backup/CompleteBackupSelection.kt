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

/** Presentation groups retain the original archive sections and saved partial selections. */
enum class CompleteBackupGroup(val title: String, val description: String, val sections: Set<CompleteBackupSection>) {
    AI_PLATFORMS(
        "AI platforms",
        "Profiles, credentials, APIs and local models.",
        setOf(CompleteBackupSection.PLATFORMS, CompleteBackupSection.CREDENTIALS, CompleteBackupSection.LOCAL_MODELS)
    ),
    CONVERSATIONS(
        "Conversations",
        "Chats, favorites, memory, attachments, tool history, statistics and Amazon data.",
        setOf(CompleteBackupSection.CONVERSATIONS, CompleteBackupSection.MEMORY, CompleteBackupSection.ATTACHMENTS, CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.STATISTICS, CompleteBackupSection.AMAZON_DATA)
    ),
    SETTINGS_AND_TOOLS(
        "Settings & tools",
        "Themes, preferences, plugins and MCP connections.",
        setOf(CompleteBackupSection.SETTINGS, CompleteBackupSection.TOOLS)
    )
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
        if (CompleteBackupSection.STATISTICS in normalized) {
            normalized += setOf(CompleteBackupSection.CONVERSATIONS, CompleteBackupSection.PLATFORMS, CompleteBackupSection.AGENT_HISTORY)
        }
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
                if (section == CompleteBackupSection.CONVERSATIONS) {
                    setOf(CompleteBackupSection.ATTACHMENTS, CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.STATISTICS)
                } else if (section in setOf(CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.PLATFORMS)) {
                    setOf(CompleteBackupSection.STATISTICS)
                } else {
                    emptySet()
                }
        }
        return copy(sections = updated).normalized()
    }

    fun toggled(group: CompleteBackupGroup, enabled: Boolean): CompleteBackupSelection = if (enabled) {
        copy(sections = sections + group.sections).normalized()
    } else {
        group.sections.fold(this) { selection, section -> selection.toggled(section, false) }
    }

    companion object {
        val DEFAULT_SECTIONS = CompleteBackupSection.entries.toSet() - setOf(
            CompleteBackupSection.CREDENTIALS,
            CompleteBackupSection.MEMORY,
            CompleteBackupSection.LOCAL_MODELS,
            CompleteBackupSection.ATTACHMENTS,
            CompleteBackupSection.AGENT_HISTORY,
            CompleteBackupSection.AMAZON_DATA
        )
        val ALL = CompleteBackupSelection(CompleteBackupSection.entries.toSet())
    }
}
