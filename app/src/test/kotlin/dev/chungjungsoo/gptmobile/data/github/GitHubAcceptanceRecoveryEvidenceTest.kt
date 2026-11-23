package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAcceptanceRecoveryEvidenceTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test fun retainedDeadlineReportsRelativeTimeOnlyWhenObserved() {
        val contract = json("""{"accepted_at_ms":1000,"deadline_at_ms":61000}""")
        val expired = GitHubExecutionLimits.deadlineTiming(contract, 66000)
        assertEquals(json("""{"task_deadline_observation":"WINDOW_EXPIRED","task_deadline_clock":"WALL_CLOCK","task_deadline_renewed":false,"task_deadline_window_seconds":60,"task_deadline_elapsed_seconds":65,"task_deadline_remaining_seconds":0,"task_deadline_overdue_seconds":5}"""), expired)
        val open = GitHubExecutionLimits.deadlineTiming(contract, 60000)
        assertEquals(JsonPrimitive("WINDOW_OPEN"), open["task_deadline_observation"])
        assertNull(open["task_deadline_overdue_seconds"])
        assertEquals(json("""{"task_deadline_observation":"NO_ACCEPTED_DEADLINE"}"""), GitHubExecutionLimits.deadlineTiming(json("{}"), 66000))
        assertEquals(json("""{"task_deadline_observation":"CONTRACT_UNAVAILABLE"}"""), GitHubExecutionLimits.deadlineTiming(null, 66000))
        val retained = GitHubAcceptanceRecoveryEvidence.fields(JsonObject(mapOf("original_failure" to JsonObject(expired + json("""{"stop_reason":"ACCEPTED_TASK_DEADLINE_REACHED","task_deadline_check":"BEFORE_TOOL_RESTORE","deadline_at_ms":61000}""")))))
        assertEquals(JsonObject(expired + ("task_deadline_check" to JsonPrimitive("BEFORE_TOOL_RESTORE"))), retained)
        assertTrue(GitHubAcceptanceRecoveryEvidence.fields(json("""{"original_failure":{"stop_reason":"OTHER","task_deadline_check":"BEFORE_TOOL_RESTORE"}}""")).isEmpty())
    }

    @Test fun missingProtectionIsNotInferredFromUnprotectedBranchAndObservedRules() {
        val policy = json("""{"branch":{"protected":false},"protection":{"available":false,"http_status":404},"rules":{"available":true,"data":[]},"policy":{"required_checks_complete":false}}""")
        val evidence = GitHubStatusPolicyEvidence.snapshot(policy)
        assertEquals(JsonPrimitive("NOT_FOUND_OR_INACCESSIBLE"), evidence["branch_protection_observation"])
        assertEquals(JsonPrimitive("OBSERVED"), evidence["branch_rules_observation"])
        assertEquals(JsonPrimitive(404), evidence["status_policy_protection_http_status"])
        assertEquals(JsonPrimitive(false), evidence["status_branch_protected"])
        assertEquals(JsonPrimitive(false), evidence["status_policy_complete"])
        assertEquals(JsonPrimitive("CHECK_PROTECTION_READ_PERMISSION"), evidence["status_policy_recovery_hint"])
        assertFalse("VERIFIED_NONE" in evidence.toString())
        assertEquals(JsonPrimitive(false), (policy["policy"] as JsonObject)["required_checks_complete"])
    }

    @Test fun recoveryNamesTheActualPolicyEndpointAndAuthenticationFailure() {
        for ((protection, rules, expected) in listOf(
            Triple(403, 403, "CHECK_POLICY_READ_PERMISSIONS"),
            Triple(401, 403, "CHECK_AUTHENTICATION"),
            Triple(200, 403, "CHECK_RULES_READ_PERMISSION"),
            Triple(429, 200, "WAIT_RATE_LIMIT")
        )) {
            fun observation(status: Int, body: String) = if (status == 200) """{"available":true,"data":$body}""" else """{"available":false,"http_status":$status}"""
            val policy = json("""{"protection":${observation(protection, "{}")},"rules":${observation(rules, "[]")},"policy":{"required_checks_complete":false}}""")
            val evidence = GitHubStatusPolicyEvidence.snapshot(policy)
            assertEquals(JsonPrimitive(expected), evidence["status_policy_recovery_hint"])
        }
    }

    @Test fun absentAndMalformedObservationsDoNotInventHttpStatusOrCompleteness() {
        val absent = GitHubStatusPolicyEvidence.snapshot(null)
        assertEquals(JsonPrimitive("UNOBSERVED"), absent["branch_protection_observation"])
        assertNull(absent["status_policy_protection_http_status"])
        assertNull(absent["status_policy_complete"])
        assertNull(absent["status_policy_recovery_hint"])
        val malformed = GitHubStatusPolicyEvidence.snapshot(json("""{"protection":{"available":false,"http_status":"404"},"rules":{"available":true,"data":[{}]},"branch":{"protected":"false"},"policy":{"required_checks_complete":"false"}}"""))
        assertNull(malformed["status_policy_protection_http_status"])
        assertNull(malformed["status_branch_protected"])
        assertNull(malformed["status_policy_complete"])
        assertEquals(JsonPrimitive("INVALID_RESPONSE"), malformed["branch_rules_observation"])
    }

    @Test fun completePolicyDoesNotRequestAnotherPermissionChange() {
        val evidence = GitHubStatusPolicyEvidence.snapshot(json("""{"protection":{"available":true,"data":{}},"rules":{"available":true,"data":[]},"policy":{"required_checks_complete":true}}"""))
        assertEquals(JsonPrimitive(true), evidence["status_policy_complete"])
        assertNull(evidence["status_policy_recovery_hint"])
    }

    @Test fun retainedPrerequisitePlanReportsReadyReadsAndFixtureGateWithoutApprovingIt() {
        val verdict = json(
            """{"completion_verified":false,"acceptance_ledger":{"capabilities":[
                {"id":"github_ci:logs","required":true,"state":"BLOCKED"},
                {"id":"github_ci:failure","required":true,"state":"BLOCKED"},
                {"id":"github_read:read","required":true,"state":"NOT_TESTED"},
                {"id":"github_commit:create","required":false,"state":"BLOCKED"},
                {"id":"github_pr:list","required":true,"state":"PASS"},
                {"id":"private-account/private-repository:private-path","required":true,"state":"BLOCKED"}
            ]},"test_checklist":{"preparation":{"schema":"github.integration-preparation.v1","status":"PREREQUISITES_REQUIRED","ready_read_count":0,"additional_ready_read_count":0,"owned_fixture_creation_admissible":false,"budget":{"remaining_known":true,"acquisition_allowed":false,"cleanup_reserve":1,"fixture_start_minimum":6},"blockers":[{"code":"OWNED_FIXTURE_REQUIRED","required_capability_ids":["private-id"]},{"code":"APPROVAL_REQUIRED","required_capability_ids":[]},{"code":"private blocker","required_capability_ids":["private-id"]}],"ready_reads":[{"arguments":{"repository":"private-repo"}}]}}}"""
        )
        val original = verdict.toString()
        val evidence = GitHubAcceptanceRecoveryEvidence.fields(verdict)
        assertEquals(JsonPrimitive("PREREQUISITES_REQUIRED"), evidence["acceptance_preparation_state"])
        assertEquals(JsonPrimitive(0), evidence["acceptance_ready_read_count"])
        assertEquals(JsonPrimitive(false), evidence["acceptance_fixture_creation_admissible"])
        assertEquals(JsonPrimitive(false), evidence["acceptance_acquisition_allowed"])
        assertEquals(JsonPrimitive(1), evidence["acceptance_cleanup_reserve"])
        assertEquals(JsonPrimitive(6), evidence["acceptance_fixture_start_minimum"])
        assertEquals(JsonArray(listOf(JsonPrimitive("OWNED_FIXTURE_REQUIRED"))), evidence["acceptance_prerequisite_blockers"])
        assertEquals(JsonArray(listOf(JsonPrimitive("github_ci"), JsonPrimitive("github_read"))), evidence["acceptance_blocked_operation_families"])
        assertFalse("private" in evidence.toString())
        assertFalse("arguments" in evidence.toString())
        assertEquals(original, verdict.toString())
        assertEquals(JsonPrimitive(false), verdict["completion_verified"])
    }

    @Test fun noPreparationSnapshotDoesNotInferZeroOrPermissionFromAStopCode() {
        val evidence = GitHubAcceptanceRecoveryEvidence.fields(json("""{"stop_reason":{"code":"CAPABILITY_PREREQUISITES_MISSING"},"test_checklist":{"preparation":{"status":"PREREQUISITES_REQUIRED","ready_read_count":0,"owned_fixture_creation_admissible":true}}}"""))
        assertTrue(evidence.isEmpty())
    }

    @Test fun aBoundedFamilySampleReportsItsOmittedCountAndStripsConnectionAliases() {
        val families = GitHubAcceptanceRecoveryEvidence.operationFamilies
        val capabilities = families.map { family -> json("""{"id":"${family}__private-connection:read","required":true,"state":"BLOCKED"}""") }
        val verdict = JsonObject(mapOf("acceptance_ledger" to JsonObject(mapOf("capabilities" to JsonArray(capabilities)))))
        val evidence = GitHubAcceptanceRecoveryEvidence.fields(verdict)
        assertEquals(8, (evidence["acceptance_blocked_operation_families"] as JsonArray).size)
        assertEquals(JsonPrimitive(families.size - 8), evidence["acceptance_additional_blocked_family_count"])
        assertFalse("private-connection" in evidence.toString())
    }

    @Test fun invalidPreparationValuesAndUnboundStatusCoverageAreExcluded() {
        val evidence = GitHubAcceptanceRecoveryEvidence.fields(json("""{"test_checklist":{"preparation":{"schema":"github.integration-preparation.v1","status":"private text","ready_read_count":-1,"additional_ready_read_count":"0","owned_fixture_creation_admissible":"true"}},"status_coverage":[{"item":"Other policy","status_policy_protection_http_status":404}]}"""))
        assertTrue(evidence.isEmpty())
    }

    @Test fun policyProjectionKeepsOnlyClosedObservedFields() {
        val evidence = GitHubAcceptanceRecoveryEvidence.fields(json("""{"status_coverage":[{"item":"Required-check policy","status_policy_protection_http_status":404,"status_policy_rules_http_status":999,"status_branch_protected":false,"status_policy_complete":false,"status_policy_recovery_hint":"CHECK_PROTECTION_READ_PERMISSION","reason":"private server response"}]}"""))
        assertEquals(JsonPrimitive(404), evidence["status_policy_protection_http_status"])
        assertNull(evidence["status_policy_rules_http_status"])
        assertEquals(JsonPrimitive(false), evidence["status_policy_complete"])
        assertFalse("private" in evidence.toString())
        assertTrue(evidence.keys.all { it in GitHubAcceptanceRecoveryEvidence.fields })
    }
}
