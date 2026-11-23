package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubFixtureApprovalPreflightTest {
    private val binding = "fixture-policy-task"
    private val repository = "owner/repo"
    private val branch = "main"

    private fun book(
        saved: MutableMap<String, JsonObject>,
        decision: GitHubDecisionMode?,
        revision: String?
    ) = GitHubEvidenceBook(
        binding = binding,
        repository = repository,
        branch = branch,
        trustedTools = emptySet(),
        fixtureDecisionMode = decision,
        authorizationRevision = revision,
        runtimeIdentity = { buildJsonObject { put("application_id", "test-app") } },
        load = { saved[it] },
        save = { key, value ->
            if (!GitHubEvidenceBook.currentSnapshot(key) && key in saved) assertEquals("Immutable approval changed", saved[key], value)
            saved[key] = value
        }
    )

    private fun stored(value: JsonObject) = mutableMapOf(
        "fixture_preflight_" + GitHubEvidenceBook.digest(binding).take(24) to value
    )

    @Test fun unknownSavedModeCanRecheckWithKnownTaskBoundApprovalRevision() = runTest {
        val saved = stored(GitHubTestChecklist.fixtureApprovalPreflight(null, binding, repository, branch, buildJsonObject { }))
        val original = saved.toMap()
        val current = book(saved, GitHubDecisionMode.AUTO_APPROVE, "current-task-authorization")
        val result = current.fixtureApprovalPreflight()
        assertEquals(JsonPrimitive("READY"), result["state"])
        assertEquals(JsonPrimitive("AUTO_APPROVE"), result["decision_mode"])
        assertEquals(JsonPrimitive("TASK_BOUND_AUTHORIZATION_REVISION"), result["recheck_source"])
        assertEquals(result, current.fixtureApprovalPreflight())
        assertEquals(original.values.single(), saved[original.keys.single()])
        assertEquals(result, book(saved, GitHubDecisionMode.ASK, "another-revision").fixtureApprovalPreflight())
    }

    @Test fun missingAuthorityNeverPromotesUnknownModeToAnAutomaticWrite() = runTest {
        val saved = stored(GitHubTestChecklist.fixtureApprovalPreflight(null, binding, repository, branch, buildJsonObject { }))
        val result = book(saved, GitHubDecisionMode.AUTO_APPROVE, null).fixtureApprovalPreflight()
        assertEquals(JsonPrimitive("UNKNOWN"), result["state"])
        assertEquals(JsonPrimitive("UNKNOWN"), result["decision_mode"])
        assertEquals(JsonPrimitive(false), result["mutation_dispatched"])
    }

    @Test fun existingKnownApprovalRemainsFrozenDuringResume() = runTest {
        val manual = GitHubTestChecklist.fixtureApprovalPreflight(GitHubDecisionMode.ASK, binding, repository, branch, buildJsonObject { })
        val saved = stored(manual)
        val resumed = book(saved, GitHubDecisionMode.AUTO_APPROVE, "a-new-revision")
        assertEquals(manual, resumed.fixtureApprovalPreflight())
        assertEquals(JsonPrimitive("CONDITIONAL"), resumed.fixtureApprovalPreflight()["state"])
    }

    @Test fun staleConditionalUnknownCannotImpersonateManualApproval() = runTest {
        val unknown = GitHubTestChecklist.fixtureApprovalPreflight(null, binding, repository, branch, buildJsonObject { })
        val saved = stored(JsonObject(unknown + ("state" to JsonPrimitive("CONDITIONAL"))))
        val result = book(saved, null, null).fixtureApprovalPreflight()
        assertEquals(JsonPrimitive("UNKNOWN"), result["state"])
        assertEquals(JsonPrimitive("UNKNOWN"), result["decision_mode"])
    }
}
