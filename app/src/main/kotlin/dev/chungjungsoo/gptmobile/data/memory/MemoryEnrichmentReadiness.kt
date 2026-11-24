package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.knowledge.MemoryPrivacyPolicy

/** A worker can succeed by persisting a deferral. Readiness requires semantic evidence. */
internal fun memoryEnrichmentReady(workerState: String, outcome: String?, enrichmentComplete: Boolean): Boolean =
    workerState == "SUCCEEDED" && enrichmentComplete && outcome in setOf("SUCCESS", "PROCESSED_NO_NEW_FACTS", "SKIPPED")

/** Content-free provenance; project names, source text, inferred facts and hashes are excluded. */
internal data class MemoryEnrichmentProvenance(val sourceChatId: Int, val scopeClass: String, val ownerChatId: Int?) {
    fun diagnosticFields(): String = "sourceChatId=$sourceChatId · memoryScopeClass=$scopeClass · scopeOwnerChatId=${ownerChatId ?: "none"} · processingDestination=ON_DEVICE · scopeAuthority=CONVERSATION_AT_EXECUTION"

    companion object {
        fun observed(chatId: Int, scope: String): MemoryEnrichmentProvenance = MemoryEnrichmentProvenance(
            sourceChatId = chatId,
            scopeClass = when {
                scope == "private:unshared" -> "PRIVATE_UNSHARED"
                MemoryPrivacyPolicy.isPrivate(scope) -> "PRIVATE_CONVERSATION"
                scope.startsWith("project:") -> "PROJECT"
                else -> "PERSONAL"
            },
            ownerChatId = MemoryPrivacyPolicy.owner(scope)
        )
    }
}
