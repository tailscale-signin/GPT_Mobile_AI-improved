package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
    val sourceQueueRunId: String? = null
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(value: String?): CombinedAcceptedTurn? {
            if (value.isNullOrBlank()) return null
            return runCatching { json.decodeFromString<CombinedAcceptedTurn>(value) }
                .getOrNull()?.takeIf { it.version == 1 && it.policyRecordKind == "COMBINED_ACCEPTED_TURN" }
        }

        fun context(value: String?): CombinedExecutionContext? = decode(value)?.context ?: runCatching {
            val record = json.parseToJsonElement(value.orEmpty()) as? JsonObject
            record?.get("combined")?.let { json.decodeFromJsonElement(CombinedExecutionContext.serializer(), it) }
        }.getOrNull()
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
