package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A verifier enum is not an HTTP credential. All other authorization fields remain secret. */
internal fun diagnosticSemanticAuthorization(parent: JsonObject, key: String, value: JsonElement): Boolean {
    if (key != "authorization" || (value as? JsonPrimitive)?.contentOrNull !in setOf("AUTHORIZED", "NOT_AUTHORIZED", "DENIED", "UNKNOWN")) return false
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
