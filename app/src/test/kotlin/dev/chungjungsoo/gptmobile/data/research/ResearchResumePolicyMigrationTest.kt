package dev.chungjungsoo.gptmobile.data.research

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchResumePolicyMigrationTest {
    private val legacy = researchHash("original current settings and permissions")
    private val current = "dispatch-v2:${researchHash("current dispatch policy")}"

    private fun task() = AcceptedResearchTask(
        taskId = researchHash("task:1:10"), rootWorkId = "1:10", chatId = 1,
        originatingUserMessageId = 10, taskText = "Resume the saved repository task",
        intent = "REQUIRED", participantUids = listOf("selected", "other"), primaryUid = "selected",
        settingsRevision = legacy, sourceAuthorityRevision = "accepted-authority", acceptedAt = 100
    )

    private fun original() = ResearchResumeRequest("action", task().taskId, "1:10", "saved-checkpoint", "RETRY_FAILED_STAGE", "selected", "saved-evidence", legacy, 200)

    @Test fun unchangedLegacyPolicyMigratesOnceAndRetainsOriginalLineageAcrossRestart() = runBlocking {
        val directory = Files.createTempDirectory("research-resume-migration").toFile()
        try {
            val first = ResearchTaskStore(directory)
            first.accept(task(), true)
            first.resume(original(), true)
            val next = original().copy(policyRevision = current, checkpointId = "refreshed-display", evidenceRevision = "new-display", requestedAt = 300)
            val expected = original().copy(policyRevision = current)
            assertEquals(expected, ResearchTaskStore(directory).resume(next, true, currentLegacyPolicyRevision = legacy))
            assertEquals(expected, ResearchTaskStore(directory).resume(next.copy(requestedAt = 400), true))
            val saved = directory.listFiles().orEmpty().single { it.extension == "resume" }
            assertEquals(expected, Json.decodeFromString<ResearchResumeRequest>(saved.readText()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun changedCurrentPermissionsOrProfileCannotMigrateALegacyAction() = runBlocking {
        val directory = Files.createTempDirectory("research-resume-revocation").toFile()
        try {
            val store = ResearchTaskStore(directory)
            store.accept(task(), true)
            store.resume(original(), true)
            val next = original().copy(policyRevision = current)
            listOf(null, researchHash("revoked permissions"), researchHash("changed profile"), "not-a-policy-hash").forEach { observed ->
                val failure = runCatching { store.resume(next, true, currentLegacyPolicyRevision = observed) }.exceptionOrNull()
                assertEquals("RESEARCH_RECOVERY_ACTION_CHANGED", failure?.message)
                assertEquals(original(), ResearchTaskStore(directory).resume(original(), true))
            }
            assertTrue(runCatching { store.resume(next.copy(destinationProfileUid = "unaccepted"), true, currentLegacyPolicyRevision = legacy) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun migrationRejectsChangedStageAndEveryMismatchedSavedActionIdentity() = runBlocking {
        val directory = Files.createTempDirectory("research-resume-identity").toFile()
        try {
            val store = ResearchTaskStore(directory)
            store.accept(task(), true)
            store.resume(original(), true)
            val next = original().copy(policyRevision = current)
            assertEquals(
                "RESEARCH_RECOVERY_ACTION_CHANGED",
                runCatching { store.resume(next.copy(requestedStage = "RETRY_WRITING"), true, currentLegacyPolicyRevision = legacy) }.exceptionOrNull()?.message
            )
            val saved = directory.listFiles().orEmpty().single { it.extension == "resume" }
            val identities = listOf(
                original().copy(taskId = researchHash("another-task")),
                original().copy(rootWorkId = "1:11"),
                original().copy(actionId = "another-action"),
                original().copy(requestedStage = "RETRY_WRITING"),
                original().copy(destinationProfileUid = "other")
            )
            identities.forEach { changed ->
                saved.writeText(Json.encodeToString(changed))
                val failure = runCatching { ResearchTaskStore(directory).resume(next, true, currentLegacyPolicyRevision = legacy) }.exceptionOrNull()
                assertEquals("RESEARCH_RECOVERY_ACTION_CHANGED", failure?.message)
                assertEquals(changed, Json.decodeFromString<ResearchResumeRequest>(saved.readText()))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun migrationCannotAuthorizeANewDispatchPolicyOrDowngradeAnExistingOne() = runBlocking {
        val directory = Files.createTempDirectory("research-resume-version").toFile()
        try {
            val store = ResearchTaskStore(directory)
            store.accept(task(), true)
            store.resume(original(), true)
            listOf("dispatch-v2:bad", "dispatch-v3:${researchHash("another policy")}", researchHash("another legacy hash")).forEach { revision ->
                assertEquals(
                    "RESEARCH_RECOVERY_ACTION_CHANGED",
                    runCatching { store.resume(original().copy(policyRevision = revision), true, currentLegacyPolicyRevision = legacy) }.exceptionOrNull()?.message
                )
            }
            val migrated = store.resume(original().copy(policyRevision = current), true, currentLegacyPolicyRevision = legacy)
            listOf("dispatch-v2:${researchHash("changed grants")}", legacy).forEach { revision ->
                assertEquals(
                    "RESEARCH_RECOVERY_ACTION_CHANGED",
                    runCatching { store.resume(migrated.copy(policyRevision = revision), true, currentLegacyPolicyRevision = current) }.exceptionOrNull()?.message
                )
                assertEquals(migrated, ResearchTaskStore(directory).resume(migrated, true))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
