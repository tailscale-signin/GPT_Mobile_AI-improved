package dev.chungjungsoo.gptmobile.data.research

/** Only unambiguous conversational turns bypass research; substantive requests remain eligible. */
fun isResearchConversationOnly(task: String): Boolean = task.trim().lowercase().trimEnd('.', '!', '?') in
    setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening", "thanks", "thank you", "ok", "okay", "test", "testing", "connection test", "test connection")
