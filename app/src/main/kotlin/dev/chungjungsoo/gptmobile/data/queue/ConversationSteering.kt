package dev.chungjungsoo.gptmobile.data.queue

import java.util.Locale

/** Only complete, unambiguous control phrases can stop a task or request its live status. */
internal enum class ConversationSteering {
    STATUS,
    CANCEL,
    CORRECTION,
    ADDITION;

    companion object {
        private val status = setOf("status update", "task status", "what is the status of this task", "what's the status of this task", "how is this task going", "how is the current task going")
        private val cancellation = setOf("stop this task", "stop the current task", "cancel this task", "cancel the current task")
        private val correction = Regex("""^(?:correction(?:[,:]|\s)|actually[, ]|i meant\s|instead[, ])""")
        fun from(text: String): ConversationSteering {
            val instruction = text.trim().lowercase(Locale.ROOT).trimEnd('?', '.', '!').removePrefix("please ").trimEnd()
            return when {
                instruction in status -> STATUS
                instruction in cancellation -> CANCEL
                correction.containsMatchIn(instruction) -> CORRECTION
                else -> ADDITION
            }
        }
    }
}
