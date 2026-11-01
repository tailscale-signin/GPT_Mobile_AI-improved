package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance

/** Never remove a privacy label while leaving its text (or later derived replies) in the request. */
internal object GitHubPersonalContextIsolation {
    data class RequestHistory(val users: List<MessageV2>, val assistants: List<List<MessageV2>>, val fresh: Boolean)

    fun project(users: List<MessageV2>, history: List<List<MessageV2>>, connectionUid: String, retained: List<PersonalDataProvenance>): RequestHistory {
        val chatId = users.lastOrNull()?.chatId
        val fresh = requiresFreshTurn(history, connectionUid, chatId) ||
            retained.any { it.localOnly && !repositoryMemory(it, connectionUid, chatId) }
        return if (fresh) RequestHistory(users.takeLast(1), emptyList(), true) else RequestHistory(users, history, false)
    }

    fun requiresFreshTurn(history: List<List<MessageV2>>, connectionUid: String, chatId: Int?): Boolean =
        history.flatten().any { message ->
            val items = message.timeline + message.revisions.flatMap { it.timeline }
            message.combinedSources.any { it.localOnly } ||
                items.any { item ->
                    item.recalledFacts.any { it.localOnly } ||
                        item.personalData.any { label ->
                            label.localOnly && !repositoryMemory(label, connectionUid, chatId)
                        }
                }
        }

    fun repositoryMemory(label: PersonalDataProvenance, connectionUid: String, chatId: Int?): Boolean =
        label.sourceToolId == "github_engineering_memory" &&
            label.sourceConnectionUid == connectionUid &&
            chatId != null &&
            label.retentionChatId == chatId
}
