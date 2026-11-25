package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageReply

/** A message/run change projects only the changed source; UI meter/phase events reuse all results. */
internal class VoiceReplyProjectionCache {
    class Entry(val source: MessageV2, val status: String?, val reply: VoiceMessageReply) {
        var terminalPublished = false
    }
    private val entries = mutableMapOf<Int, Entry>()
    private var previousGroups: List<List<MessageV2>>? = null
    private var previousStatusVersion: Any? = null
    private var previousReplies = emptyList<VoiceMessageReply>()

    fun update(groups: List<List<MessageV2>>, statusVersion: Any, status: (String) -> String?): List<VoiceMessageReply> {
        if (groups === previousGroups && statusVersion === previousStatusVersion) return previousReplies
        val present = HashSet<Int>()
        val result = buildList {
            for (group in groups) {
                for (message in group) {
                    val run = message.currentRunId?.takeIf(String::isNotBlank) ?: continue
                    present += message.id
                    val state = status(run)
                    val cached = entries[message.id]
                    if (cached != null && cached.source == message && cached.status == state) {
                        add(cached.reply)
                    } else {
                        val reply = VoiceMessageReply(VoiceMessageIdentity(message.id, run), ConversationSubject.withoutMetadata(message.content), state in AgentRunStatus.terminal, state in AgentRunStatus.terminal && state != AgentRunStatus.COMPLETED)
                        entries[message.id] = Entry(message, state, reply)
                        add(reply)
                    }
                }
            }
        }
        entries.keys.retainAll(present)
        previousGroups = groups
        previousStatusVersion = statusVersion
        previousReplies = result
        return result
    }

    fun pendingTerminal(id: Int): Entry? = entries[id]?.takeIf { it.reply.complete && !it.terminalPublished }
}
