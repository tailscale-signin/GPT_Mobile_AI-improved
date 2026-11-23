package dev.chungjungsoo.gptmobile.data.privacy

/** Reload durable authority before an external workflow that cannot mask private conversation data. */
internal suspend fun requireUnprotectedConversationPrivacy(
    chatId: Int?,
    loadPrivacyLock: suspend (Int) -> Boolean?
): ConversationPrivacyContext {
    // A caller cannot move private source text into another ordinary chat or an unbound job.
    PrivacyModeStore.requireUnprotectedTransport()
    if (chatId != null) {
        require(chatId > 0) { "The saved conversation is unavailable. No external work was started." }
        val locked = checkNotNull(loadPrivacyLock(chatId)) {
            "The saved conversation is unavailable. No external work was started."
        }
        if (locked) throw UnverifiedServiceTransport()
    }
    return ConversationPrivacyContext(chatId, false)
}
