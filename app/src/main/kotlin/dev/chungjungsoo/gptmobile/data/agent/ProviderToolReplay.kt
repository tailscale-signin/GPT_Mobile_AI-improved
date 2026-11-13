package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Protected replay data, never a model-supplied execution instruction. */
@Serializable
data class ProviderToolInvocation(val name: String, val arguments: JsonObject) {
    fun fingerprint(callId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$callId:$name:$arguments".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Capture at the runner boundary before changing execution names or arguments. */
internal fun ProviderEvent.ToolCall.captureProviderInvocation(): ProviderEvent.ToolCall {
    val original = ProviderToolInvocation(name, arguments)
    return copy(originalInvocationFingerprint = original.fingerprint(callId), originalInvocation = original)
}

/** A hash alone cannot reconstruct a legacy call changed by routing. Never guess its envelope. */
internal fun ProviderEvent.ToolCall.providerInvocation(): ProviderToolInvocation {
    val original = originalInvocation ?: ProviderToolInvocation(name, arguments)
    check(originalInvocationFingerprint == null && originalInvocation == null || original.fingerprint(callId) == originalInvocationFingerprint) {
        "CLIENT_TOOL_RESULT_IDENTITY_MISMATCH: The original provider call is unavailable or changed. Restore its verified gateway handoff before returning the saved result; no operation was replayed."
    }
    return original
}
