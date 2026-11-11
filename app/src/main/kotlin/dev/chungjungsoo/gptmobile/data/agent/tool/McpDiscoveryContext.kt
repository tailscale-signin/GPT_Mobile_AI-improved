package dev.chungjungsoo.gptmobile.data.agent.tool

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Identifies who requested metadata refresh; never grants permission or bypasses a health gate. */
internal class McpDiscoveryContext(
    val trigger: McpDiscoveryTrigger,
    val beforeOwnedDispatch: (suspend () -> Unit)? = null,
    val onSource: (McpDiscoverySource) -> Unit = {}
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpDiscoveryContext>
}

internal enum class McpDiscoverySource { FRESH, SHARED, CACHED }

internal data class McpDiscoveryReceipt(val tools: List<Tool>, val source: McpDiscoverySource)

internal enum class McpDiscoveryTrigger { AUTOMATIC_RESOLUTION, AUTOMATIC_HEALTH, USER_RECONNECT, AUDIT_REFRESH, OAUTH_REFRESH, EXPLICIT_UNSPECIFIED }

internal enum class McpDiscoveryDecision { REQUESTED, PAUSED_CONFIGURATION, PAUSED_AUTHENTICATION, WAITING_BACKOFF, LIVE_CACHE, SHARED_PRODUCER, START_ACQUISITION }

/** Only opaque saved-record identity is logged, never endpoint, authorization, or transport key. */
internal fun mcpDiscoveryDiagnostic(config: McpConnectionConfig, trigger: McpDiscoveryTrigger, force: Boolean, decision: McpDiscoveryDecision): String {
    val connection = MessageDigest.getInstance("SHA-256").digest(config.connectionUid.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    val revision = config.configurationRevision?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: "unobserved"
    return "DISCOVERY_REQUEST · connectionHash=$connection · trigger=$trigger · forceRefresh=$force · configurationRevision=$revision · decision=$decision"
}

/** Schema shape only: arbitrary keys, server descriptions and malformed values never enter logs. */
internal fun mcpDiscoveryResponseShape(discovery: JsonObject): String {
    val known = setOf("supportedVersions", "capabilities", "serverInfo", "resultType", "instructions")
    val versions = discovery["supportedVersions"]
    val type = when (versions) {
        null -> "missing"
        is JsonArray -> "array"
        else -> "invalid_type"
    }
    val revisions = (versions as? JsonArray)?.take(8)?.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString && value.content.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }?.content ?: "invalid" }.orEmpty()
    return "fields=${discovery.keys.filter { it in known }.sorted().joinToString(",")} · unrecognizedFieldCount=${discovery.keys.count { it !in known }} · supportedVersionsType=$type · supportedVersionCount=${(versions as? JsonArray)?.size ?: 0} · protocolSummary=${revisions.joinToString(",")}"
}
