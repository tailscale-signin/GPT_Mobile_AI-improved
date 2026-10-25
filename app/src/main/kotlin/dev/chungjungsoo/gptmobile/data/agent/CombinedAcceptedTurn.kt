package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stored before contributor dispatch in the existing durable run snapshot column. */
@Serializable
data class CombinedAcceptedTurn(
    val version: Int = 1,
    val policyRecordKind: String = "COMBINED_ACCEPTED_TURN",
    val context: CombinedExecutionContext,
    val tools: ChatMcpToolConfig,
    val parentRootBudgetId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val acceptedTask: AcceptedTaskRef? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val recoveredProviderAnswer: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val sourceQueueRunId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val revisionOf: CombinedAnswerRevisionTarget? = null
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(value: String?): CombinedAcceptedTurn? = CombinedAcceptedTurnCache.read(value)?.accepted

        fun context(value: String?): CombinedExecutionContext? = CombinedAcceptedTurnCache.read(value)?.context

        /** DAO readers invoke this on IO before an observable snapshot reaches the UI. */
        internal fun prepareDecoded(value: String?, chatId: Int) {
            CombinedAcceptedTurnCache.read(value, chatId)
        }
        internal fun forgetDecodedChat(chatId: Int) = CombinedAcceptedTurnCache.forgetChat(chatId)
    }
}

/** Null is a captured provider-default temperature, not permission to adopt a later profile edit. */
fun CombinedRequestedPolicy.temperatureFor(profileUid: String, fallback: Float?): Float? =
    if (requestedTemperatures.containsKey(profileUid)) requestedTemperatures[profileUid] else settings.creativity ?: fallback

/** Contributor continuations stay contributors; only final-answer work may spend finalization funds. */
fun CombinedExecutionContext.forInvocationKind(kind: String, profileUid: String): CombinedExecutionContext = forRole(
    when {
        kind.contains("continuation") -> if (role.finalization) CombinedRunRole.CONTINUATION else role
        role == CombinedRunRole.CONTRIBUTOR && kind.replace('-', '_').contains("research_report_repair") -> CombinedRunRole.CONTRIBUTOR
        kind.contains("repair") -> CombinedRunRole.REPAIR
        kind.contains("verif") -> CombinedRunRole.VERIFIER
        else -> role
    },
    profileUid,
    workItemId
)
