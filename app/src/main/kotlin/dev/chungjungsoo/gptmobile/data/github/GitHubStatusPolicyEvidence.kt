package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Observations from the selected policy receipt, never authority or inferred absence. */
internal object GitHubStatusPolicyEvidence {
    fun snapshot(policy: JsonObject?): JsonObject = buildJsonObject {
        fun sourceState(key: String, expectsArray: Boolean): String {
            val observation = policy?.get(key) as? JsonObject ?: return "UNOBSERVED"
            if (observation["available"] == JsonPrimitive(true)) {
                val data = observation["data"]
                val valid = if (expectsArray) {
                    (data as? JsonArray)?.all { it is JsonObject && it.text("type").isNotBlank() } == true
                } else {
                    data is JsonObject
                }
                return if (valid) "OBSERVED" else "INVALID_RESPONSE"
            }
            return when (observation.httpStatus()) {
                401, 403 -> "ACCESS_DENIED"
                404 -> "NOT_FOUND_OR_INACCESSIBLE"
                429 -> "RATE_LIMITED"
                null -> "UNAVAILABLE"
                else -> "HTTP_FAILURE"
            }
        }
        val protection = sourceState("protection", expectsArray = false)
        val rules = sourceState("rules", expectsArray = true)
        put("branch_protection_observation", protection)
        put("branch_rules_observation", rules)
        for ((source, destination) in listOf("protection" to "status_policy_protection_http_status", "rules" to "status_policy_rules_http_status")) {
            (policy?.get(source) as? JsonObject)?.httpStatus()?.let { put(destination, it) }
        }
        // protected=false cannot prove absence when either policy endpoint is unavailable.
        ((policy?.get("branch") as? JsonObject)?.get("protected") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("status_branch_protected", it) }
        ((policy?.get("policy") as? JsonObject)?.get("required_checks_complete") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("status_policy_complete", it) }
        if (policy != null && (policy["policy"] as? JsonObject)?.get("required_checks_complete") != JsonPrimitive(true)) {
            val protectionDenied = protection in setOf("ACCESS_DENIED", "NOT_FOUND_OR_INACCESSIBLE")
            val rulesDenied = rules in setOf("ACCESS_DENIED", "NOT_FOUND_OR_INACCESSIBLE")
            put(
                "status_policy_recovery_hint",
                when {
                    listOf("protection", "rules").any { (policy[it] as? JsonObject)?.httpStatus() == 401 } -> "CHECK_AUTHENTICATION"
                    protectionDenied && rulesDenied -> "CHECK_POLICY_READ_PERMISSIONS"
                    protectionDenied -> "CHECK_PROTECTION_READ_PERMISSION"
                    rulesDenied -> "CHECK_RULES_READ_PERMISSION"
                    "RATE_LIMITED" in setOf(protection, rules) -> "WAIT_RATE_LIMIT"
                    else -> "REFRESH_POLICY_OBSERVATIONS"
                }
            )
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    private fun JsonObject.httpStatus() = (get("http_status") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 100..599 }
}
