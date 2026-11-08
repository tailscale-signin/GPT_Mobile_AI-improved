package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

@Serializable
data class CurrentRequestContract(val intent: String, val originalRequestDigest: String, val answerPolicy: CombinedAnswerPolicy) {
    val conversational: Boolean get() = intent in setOf("GREETING", "TEST", "PROVIDER_TEST")

    /** An acknowledgement confirms message receipt, never an unperformed endpoint test. */
    fun acknowledgement(): String = when (intent) {
        "TEST", "PROVIDER_TEST" -> "Message received. No repository checks were run."
        else -> "Hello! How can I help?"
    }

    fun instruction(): String = when (intent) {
        "GREETING" -> "Answer the current greeting briefly. Prior topics do not create a new request."
        "PROVIDER_TEST" -> "Acknowledge this provider connection test briefly. The application will summarize observed results across selected providers. Do not continue an earlier topic."
        "TEST" -> "Acknowledge this connection test briefly. Do not continue an earlier topic."
        else -> "Answer the current user request and preserve its explicit answer constraints."
    }
    companion object {
        private fun isChatSmokeTest(text: String): Boolean {
            if (text.length > 240 || Regex("(?i)\\b(?:github|repo(?:sitory)?|plugin|integration|workflow|unit|build|ci|branch|pull request|test suite)\\b").containsMatchIn(text)) return false
            return Regex("(?i)\\b(?:random|just|only|simple|meaningless|nothing|useless)\\b").containsMatchIn(text) &&
                Regex("(?i)\\btest(?:ing)?\\b").containsMatchIn(text) &&
                Regex("(?i)\\b(?:message|chat|response|respond|see|nothing|meaning)\\b").containsMatchIn(text)
        }

        fun from(request: String): CurrentRequestContract {
            val text = request.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?')
            val intent = when {
                text in setOf("test all providers", "test selected providers", "test my providers", "check provider connections") -> "PROVIDER_TEST"
                text in setOf("test", "testing", "connection test", "test connection", "this is just a useless message to see how you treat it", "just a test message", "this is a test message", "this is just a test", "just testing the chat", "testing the chat", "test the chat", "test chat", "check the chat", "check chat") -> "TEST"
                isChatSmokeTest(text) -> "TEST"
                dev.chungjungsoo.gptmobile.data.research.isResearchConversationOnly(request) -> "GREETING"
                else -> "SUBSTANTIVE"
            }
            return CurrentRequestContract(intent, dev.chungjungsoo.gptmobile.data.research.researchHash(request.trim()), CombinedAnswerPolicy.from(request))
        }
    }
}
