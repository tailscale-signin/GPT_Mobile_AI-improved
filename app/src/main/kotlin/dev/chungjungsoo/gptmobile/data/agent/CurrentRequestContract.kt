package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

@Serializable
data class CurrentRequestContract(val intent: String, val originalRequestDigest: String, val answerPolicy: CombinedAnswerPolicy) {
    fun instruction(): String = when (intent) {
        "GREETING" -> "Answer the current greeting briefly. Prior topics do not create a new request."
        "TEST" -> "Acknowledge this connection test briefly. Do not continue an earlier topic."
        else -> "Answer the current user request and preserve its explicit answer constraints."
    }
    companion object {
        fun from(request: String): CurrentRequestContract {
            val text = request.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?')
            val intent = when {
                text in setOf("test", "testing", "connection test", "test connection") -> "TEST"
                dev.chungjungsoo.gptmobile.data.research.isResearchConversationOnly(request) -> "GREETING"
                else -> "SUBSTANTIVE"
            }
            return CurrentRequestContract(intent, dev.chungjungsoo.gptmobile.data.research.researchHash(request.trim()), CombinedAnswerPolicy.from(request))
        }
    }
}
