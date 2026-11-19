package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterPrivacyPolicyTest {
    @Test
    fun everyPrivacyBooleanMustBePresentWithItsLiteralStrictValue() {
        val strict = OpenRouterPrivacyPolicy.strictFields
        assertTrue(OpenRouterPrivacyPolicy.strict(strict))
        strict.forEach { (key, value) ->
            assertFalse(key, OpenRouterPrivacyPolicy.strict(JsonObject(strict - key)))
            listOf(JsonNull, JsonPrimitive((value as JsonPrimitive).content), JsonPrimitive(0), JsonPrimitive(value != JsonPrimitive(true))).forEach { invalid ->
                assertFalse("$key=$invalid", OpenRouterPrivacyPolicy.strict(JsonObject(strict + (key to invalid))))
            }
        }
    }

    @Test
    fun directCoverageRequiresTheExactKeyAndExplicitAbsenceOfOptOutAndOverride() {
        val key = "a".repeat(64)
        val protected = JsonObject(
            mapOf(
                "config" to OpenRouterPrivacyPolicy.strictFields,
                "api_key_hashes" to JsonArray(listOf(JsonPrimitive(key))),
                "opted_out_api_key_hashes" to JsonArray(emptyList()),
                "overridden_api_key_hashes" to JsonArray(emptyList())
            )
        )
        assertTrue(OpenRouterPrivacyPolicy.directlyProtects(protected, key))
        assertFalse(OpenRouterPrivacyPolicy.directlyProtects(protected, "b".repeat(64)))
        listOf("api_key_hashes", "opted_out_api_key_hashes", "overridden_api_key_hashes").forEach { field ->
            assertFalse(field, OpenRouterPrivacyPolicy.directlyProtects(JsonObject(protected - field), key))
            assertFalse(field, OpenRouterPrivacyPolicy.directlyProtects(JsonObject(protected + (field to JsonArray(listOf(JsonPrimitive(1))))), key))
        }
        listOf("opted_out_api_key_hashes", "overridden_api_key_hashes").forEach { field ->
            assertFalse(field, OpenRouterPrivacyPolicy.directlyProtects(JsonObject(protected + (field to JsonArray(listOf(JsonPrimitive(key))))), key))
        }
    }

    @Test
    fun assignmentUpdateCarriesObservedCasStateAndTouchesOnlyModelAvailability() {
        val data = JsonObject(
            mapOf(
                "opted_out" to JsonPrimitive(false),
                "policy_ids" to JsonArray(listOf(JsonPrimitive(POLICY))),
                "workspace_default_policy_ids" to JsonArray(listOf(JsonPrimitive(DEFAULT_POLICY)))
            )
        )
        val observed = requireNotNull(OpenRouterPrivacyPolicy.assignment(JsonObject(mapOf("data" to data))))

        val update = OpenRouterPrivacyPolicy.assignmentUpdate(WORKSPACE, NEW_POLICY, observed)

        assertEquals(POLICY, observed.selected)
        assertEquals(data, update["expected_assignment"])
        assertEquals(JsonPrimitive("model_availability"), update["policy_type"])
        assertEquals(JsonPrimitive(NEW_POLICY), update["policy_id"])
        assertEquals(JsonPrimitive(WORKSPACE), update["workspace_id"])
        assertEquals(JsonPrimitive(true), update["assigned"])
        assertEquals(setOf("workspace_id", "policy_type", "policy_id", "assigned", "expected_assignment"), update.keys)
    }

    @Test
    fun incompleteAmbiguousOrOptedOutAssignmentsCannotSelectAProtectedPolicy() {
        val valid = JsonObject(
            mapOf(
                "opted_out" to JsonPrimitive(false),
                "policy_ids" to JsonArray(emptyList()),
                "workspace_default_policy_ids" to JsonArray(listOf(JsonPrimitive(DEFAULT_POLICY)))
            )
        )
        fun parse(data: JsonObject) = OpenRouterPrivacyPolicy.assignment(JsonObject(mapOf("data" to data)))
        assertEquals(DEFAULT_POLICY, requireNotNull(parse(valid)).selected)
        assertNull(requireNotNull(parse(JsonObject(valid + ("opted_out" to JsonPrimitive(true))))).selected)
        valid.keys.forEach { field -> assertNull(field, parse(JsonObject(valid - field))) }
        assertNull(parse(JsonObject(valid + ("opted_out" to JsonPrimitive("false")))))
        assertNull(parse(JsonObject(valid + ("policy_ids" to JsonArray(listOf(JsonPrimitive(POLICY), JsonPrimitive(NEW_POLICY)))))))
        assertNull(parse(JsonObject(valid + ("policy_ids" to JsonArray(listOf(JsonPrimitive("not-a-policy-id")))))))
        assertNull(parse(JsonObject(valid + mapOf("opted_out" to JsonPrimitive(true), "policy_ids" to JsonArray(listOf(JsonPrimitive(POLICY)))))))
    }

    @Test
    fun policyResponseMustMatchTheRequestedIdAndModelPolicyType() {
        val data = JsonObject(
            mapOf(
                "id" to JsonPrimitive(POLICY),
                "policy_type" to JsonPrimitive("model_availability"),
                "config" to OpenRouterPrivacyPolicy.strictFields
            )
        )
        fun parse(value: JsonObject, id: String = POLICY) = OpenRouterPrivacyPolicy.policy(JsonObject(mapOf("data" to value)), id)
        assertNotNull(parse(data))
        assertNull(parse(data, NEW_POLICY))
        assertNull(parse(JsonObject(data + ("policy_type" to JsonPrimitive("budget")))))
        assertNull(parse(JsonObject(data + ("config" to JsonNull))))
        assertNull(OpenRouterPrivacyPolicy.policy(data))
    }

    companion object {
        private const val WORKSPACE = "11111111-1111-1111-1111-111111111111"
        private const val POLICY = "22222222-2222-2222-2222-222222222222"
        private const val DEFAULT_POLICY = "33333333-3333-3333-3333-333333333333"
        private const val NEW_POLICY = "44444444-4444-4444-4444-444444444444"
    }
}
