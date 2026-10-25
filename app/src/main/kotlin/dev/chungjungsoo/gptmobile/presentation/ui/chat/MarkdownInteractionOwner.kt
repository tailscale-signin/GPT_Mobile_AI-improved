package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Callbacks belong to a single renderer and the body from which their ranges were computed. */
internal class MarkdownInteractionOwner {
    var active: Boolean = true
        private set
    private var bodyDigest: String? = null

    fun update(digest: String?) {
        if (active) bodyDigest = digest
    }

    fun accepts(digest: String?): Boolean = active && digest != null && digest == bodyDigest

    fun dispose() {
        active = false
        bodyDigest = null
    }
}

/** Keep unrelated activity UI stable while the selected answer renderer changes ownership. */
internal data class AssistantRendererIdentity(
    val message: Any,
    val runId: String?,
    val fence: Long?,
    val revisionId: String?,
    val renderer: String
)
