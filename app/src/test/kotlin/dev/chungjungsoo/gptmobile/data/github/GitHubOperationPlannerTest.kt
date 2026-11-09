package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationPlannerTest {
    @Test fun lifecyclePlansQualifyExplicitInputsAndRetainedProofBeforeAnyExistingWorkspace() {
        val requests = mapOf(
            "Create a private repository" to "create_repository",
            "Fork upstream/project" to "fork_repository",
            "Import repository upstream/project with full history" to "import_repository",
            "Clone upstream/project to the gateway" to "clone_repository"
        )
        for ((request, operation) in requests) {
            val steps = GitHubOperationPlanner.plan(request).map { it.jsonObject }
            val ids = steps.map { it["id"] }
            assertFalse(request, JsonPrimitive("resolve_context") in ids)
            assertTrue(ids.indexOf(JsonPrimitive("repository_identity")) < ids.indexOf(JsonPrimitive(operation)))
            assertTrue(ids.indexOf(JsonPrimitive(operation)) < ids.indexOf(JsonPrimitive("repository_lifecycle_status")))
            val submit = steps.single { it["id"] == JsonPrimitive(operation) }
            assertEquals(JsonPrimitive("github_repository"), submit["tool"])
            assertEquals(JsonPrimitive(operation.removeSuffix("_repository")), submit["action"])
            val status = steps.last()
            assertEquals(JsonPrimitive("status"), status["action"])
            assertTrue(status.toString().contains("expected_repository_id"))
            assertTrue(status.toString().contains("operation_id"))
        }
    }

    @Test fun compoundCreationStopsAtExplicitRepositorySelectionBeforeImplementationAndTests() {
        val steps = GitHubOperationPlanner.plan("Create a private repository and implement a feature").filterIsInstance<JsonObject>()
        val ids = steps.map { it["id"] }
        val boundary = steps.last()
        assertTrue(ids.indexOf(JsonPrimitive("repository_lifecycle_status")) < ids.indexOf(JsonPrimitive("select_verified_repository")))
        assertEquals(JsonPrimitive("CHANGE_REPOSITORY"), boundary["user_action"])
        assertEquals(JsonPrimitive(true), boundary["user_input_required"])
        assertEquals(JsonPrimitive(false), boundary["automatic_dispatch"])
        assertFalse(boundary.containsKey("tool"))
        assertFalse(JsonPrimitive("bind_created_repository") in ids)
        assertFalse(JsonPrimitive("resolve_context") in ids)
        assertFalse(JsonPrimitive("stage_patch") in ids)
        assertTrue(GitHubRepositoryAcceptance.requiresAdditionalEvidence("Create a private repository and implement a feature"))
    }

    @Test fun ordinaryRepositoryWorkKeepsItsExistingPlanAndExplanationDoesNotCreateAnything() {
        assertEquals(JsonPrimitive("resolve_context"), GitHubOperationPlanner.plan("Fix the repository bug").first().jsonObject["id"])
        assertFalse(GitHubOperationPlanner.plan("Explain how to create a repository").any { it.jsonObject["id"] == JsonPrimitive("create_repository") })
    }
}
