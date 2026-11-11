package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A verifier enum is not an HTTP credential. All other authorization fields remain secret. */
internal fun diagnosticSemanticAuthorization(parent: JsonObject, key: String, value: JsonElement): Boolean {
    if (key != "authorization" || (value as? JsonPrimitive)?.contentOrNull !in setOf("AUTHORIZED", "NOT_AUTHORIZED", "DENIED", "UNKNOWN", "REVALIDATION_REQUIRED")) return false
    if (parent.keys.all { it in setOf("schema", "authorization", "authorization_state", "discovered", "availability", "tested", "successfully_tested") } &&
        listOf("discovered", "tested", "successfully_tested").all { parent[it] in setOf(JsonPrimitive(true), JsonPrimitive(false)) } &&
        (parent["availability"] as? JsonPrimitive)?.contentOrNull in setOf("AVAILABLE", "UNAVAILABLE", "UNKNOWN") &&
        (parent["schema"] == null || parent["schema"] == JsonPrimitive("github.capability-readiness.v1")) &&
        (parent["authorization_state"] == null || parent["authorization_state"] == value)
    ) {
        return true
    }
    if ((parent["kind"] as? JsonPrimitive)?.contentOrNull !in setOf("REQUIREMENT", "CAPABILITY") ||
        (parent["state"] as? JsonPrimitive)?.contentOrNull !in setOf("PASS", "FAIL", "BLOCKED", "NOT_TESTED") ||
        parent["required"] !in setOf(JsonPrimitive(true), JsonPrimitive(false)) ||
        (parent["id_sha256"] as? JsonPrimitive)?.contentOrNull?.matches(semanticDigest) != true
    ) {
        return false
    }
    return (parent["evidence_receipt_digests"] as? JsonArray)?.all { (it as? JsonPrimitive)?.contentOrNull?.matches(semanticDigest) == true } == true
}

private val semanticDigest = Regex("[a-f0-9]{64}")
