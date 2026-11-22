package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAgentWorkspaceTest {
    private val sha = "a".repeat(40)
    private val jobId = "eng_" + "b".repeat(32)
    private val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Repair the accepted task", "selected", chatId = 1, requestId = "original")
    private val job = GatewayEngineeringJob(jobId, "owner/repo", "work", state = "IMPLEMENT", revision = 5, createdAt = 0.0, deadline = 100.0, message = "Working", headSha = sha)
    private val binding = GatewayEngineeringBinding("profile", "http://gateway/v1", request, job, principalId = "owner", gatewayId = "desktop")
    private val board = GatewayAgentWorkspace(1, jobId, "owner", "desktop", "owner/repo", "work", sha, 5, "IMPLEMENT", availableActions = listOf("worker_wave", "review_draft"))
    private val command = GatewayWorkspacePendingCommand("command-1", jobId, sha, 5, "worker_wave")

    @Test fun currentWorkspaceRequiresTheEntireOriginalIdentity() {
        board.requireBinding(binding)
        listOf(
            board.copy(schemaVersion = 2),
            board.copy(jobId = "eng_" + "c".repeat(32)),
            board.copy(principalId = "other"),
            board.copy(gatewayId = "another-desktop"),
            board.copy(repository = "other/repo"),
            board.copy(branch = "main"),
            board.copy(headSha = "c".repeat(40)),
            board.copy(revision = 4)
        ).forEach { assertTrue(runCatching { it.requireBinding(binding) }.isFailure) }
    }

    @Test fun newerObservationAtTheSameSourceIsAllowedWithoutRebinding() {
        board.copy(revision = 6).requireBinding(binding)
        assertTrue(runCatching { board.requireBinding(binding.copy(principalId = null)) }.isFailure)
        assertTrue(runCatching { board.requireBinding(binding.copy(gatewayId = null)) }.isFailure)
    }

    @Test fun boundedBoardRejectsExcessWorkersAndTerminals() {
        val row = JsonObject(emptyMap())
        board.copy(workers = List(8) { row }, terminals = List(3) { row }, requirements = List(256) { row }).requireBinding(binding)
        assertTrue(runCatching { board.copy(requirements = List(257) { row }).requireBinding(binding) }.isFailure)
        assertTrue(runCatching { board.copy(workers = List(9) { row }).requireBinding(binding) }.isFailure)
        assertTrue(runCatching { board.copy(terminals = List(4) { row }).requireBinding(binding) }.isFailure)
    }

    @Test fun unadvertisedOrNewServerActionsCannotBeInvoked() {
        assertTrue(board.canRun("worker_wave"))
        assertFalse(board.canRun("agent_start"))
        val upgraded = board.copy(availableActions = listOf("unknown_future_action"))
        upgraded.requireBinding(binding)
        assertFalse(upgraded.canRun("unknown_future_action"))
    }

    @Test fun outcomeUnknownAndQueuedCommandsNeverQualifyAsSettled() {
        assertFalse(command.settled)
        for (status in listOf("QUEUED", "RUNNING", "UNKNOWN")) {
            val receipt = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, sha, status)
            receipt.requireCommand(command)
            assertFalse(command.copy(receipt = receipt).settled)
        }
    }

    @Test fun terminalReceiptMustMatchItsExactCommandAndSource() {
        for (status in listOf("COMPLETED", "FAILED", "REJECTED", "CANCELLED")) {
            val receipt = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, sha, status)
            receipt.requireCommand(command)
            assertTrue(command.copy(receipt = receipt).settled)
            assertTrue(runCatching { receipt.copy(commandId = "other").requireCommand(command) }.isFailure)
            assertTrue(runCatching { receipt.copy(headSha = "c".repeat(40)).requireCommand(command) }.isFailure)
        }
    }

    @Test fun confirmedAbsenceIsAReadbackAndNeverAnAutomaticRetry() {
        val absent = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, "c".repeat(40), "NOT_FOUND", confirmedAbsent = true)
        absent.requireCommand(command)
        assertFalse(absent.settled)
        assertTrue(runCatching { absent.copy(confirmedAbsent = false).requireCommand(command) }.isFailure)
        assertTrue(runCatching { absent.copy(jobId = "foreign").requireCommand(command) }.isFailure)
    }

    @Test fun commandArgumentsCannotReplaceAdmissionIdentity() {
        requireWorkspaceArguments(buildJsonObject { put("objective", "Review the selected candidate") })
        listOf("head_sha", "action", "revision", "command_id", "request_id", "job_id", "principal_id", "gateway_id").forEach { key ->
            assertTrue(runCatching { requireWorkspaceArguments(buildJsonObject { put(key, "replacement") }) }.isFailure)
        }
        assertTrue(runCatching { requireWorkspaceArguments(buildJsonObject { put("body", "x".repeat(64_001)) }) }.isFailure)
    }

    @Test fun savedCommandMetadataNeverIncludesEncryptedArguments() {
        val saved = binding.copy(workspaceCommand = command.copy(argumentsReference = "protected-reference"))
        val encoded = Json.encodeToString(saved)
        assertTrue(encoded.contains("protected-reference"))
        assertFalse(encoded.contains("\"arguments\""))
        assertEquals(saved, Json.decodeFromString<GatewayEngineeringBinding>(encoded))
    }

    @Test fun malformedPresentationValuesRemainUnknown() {
        val value = JsonObject(mapOf("state" to JsonObject(emptyMap()), "ready" to JsonPrimitive("yes"), "calls" to JsonPrimitive("not a number")))
        assertEquals(null, value.workspaceText("state"))
        assertFalse(value.workspaceFlag("ready"))
        assertEquals(null, value.workspaceCount("calls"))
        assertTrue(value.workspaceStrings("capabilities").isEmpty())
    }

    @Test fun commandResultsNeverEnterNormalBindingMetadata() {
        val receipt = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, sha, "FAILED", result = buildJsonObject { put("body", "private repository content") }, error = buildJsonObject { put("message", "private error output") })
        val retained = command.copy(receipt = receipt.metadataOnly())
        assertTrue(retained.settled)
        assertFalse(Json.encodeToString(retained).contains("private"))
    }

    @Test fun localDismissalRequiresConfirmedAbsenceAndNeverChangesServerOutcome() {
        assertFalse(command.copy(dismissedAbsent = true).settled)
        val receipt = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, sha, "NOT_FOUND", confirmedAbsent = true)
        val dismissed = command.copy(receipt = receipt, dismissedAbsent = true)
        assertTrue(dismissed.settled)
        assertEquals("NOT_FOUND", dismissed.receipt?.status)
        assertFalse(command.copy(receipt = receipt.copy(confirmedAbsent = false), dismissedAbsent = true).settled)
    }

    @Test fun treePagesRejectMixedSourcesTraversalAndNonProgressingCursors() {
        val file = GatewayWorkspaceSourcePath("src/Main.kt", "c".repeat(40))
        val tree = GatewayWorkspaceTree(1, jobId, sha, listOf(file), 2, 0, 1)
        tree.requirePage(jobId, sha, 0)
        listOf(tree.copy(headSha = "d".repeat(40)), tree.copy(offset = 1), tree.copy(nextOffset = 0), tree.copy(paths = listOf(file.copy(path = "../secret"))), tree.copy(paths = listOf(file, file))).forEach {
            assertTrue(runCatching { it.requirePage(jobId, sha, 0) }.isFailure)
        }
    }

    @Test fun sourcePagesVerifyTheObservedBlobAndBoundedLineRange() {
        val file = GatewayWorkspaceSourcePath("src/Main.kt", "c".repeat(40))
        val page = GatewayWorkspaceSource(1, jobId, sha, file.path, file.blobSha, "val result = 1", 1, 1, 2, 2)
        page.requirePage(jobId, sha, file, 1)
        listOf(page.copy(blobSha = "d".repeat(40)), page.copy(path = "src/Other.kt"), page.copy(nextLine = 1), page.copy(startLine = 2), page.copy(endLine = 81, totalLines = 100)).forEach {
            assertTrue(runCatching { it.requirePage(jobId, sha, file, 1) }.isFailure)
        }
    }

    @Test fun acknowledgedUnknownCommandsOnlyPermitAdvertisedMaintenanceWhilePaused() {
        val unknown = command.copy(receipt = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, sha, "UNKNOWN"), argumentsReference = "protected-original")
        val retained = binding.copy(workspaceUnresolvedCommands = listOf(unknown))
        val paused = board.copy(state = "NEEDS_INPUT", availableActions = listOf("review_draft", "agent_cancel", "agent_start", "worker_wave"))
        assertTrue(paused.canRunWithUnresolvedCommands(retained, "review_draft"))
        assertTrue(paused.canRunWithUnresolvedCommands(retained, "agent_cancel"))
        assertFalse(paused.canRunWithUnresolvedCommands(retained, "agent_start"))
        assertFalse(paused.canRunWithUnresolvedCommands(retained, "worker_wave"))
        assertFalse(paused.copy(state = "IMPLEMENT").canRunWithUnresolvedCommands(retained, "agent_cancel"))
        val restored = Json.decodeFromString<GatewayEngineeringBinding>(Json.encodeToString(retained))
        assertEquals(unknown, restored.workspaceUnresolvedCommands.single())
        assertFalse(restored.workspaceUnresolvedCommands.single().settled)
    }

    @Test fun fixedReasonAndPrivateArtifactReferenceSurviveMetadataRetention() {
        val reference = buildJsonObject { put("result_ref", "artifact_" + "a".repeat(40)) }
        val receipt = GatewayWorkspaceCommandReceipt(1, jobId, command.commandId, sha, "REJECTED", reason = "STALE_SOURCE", resultReference = reference)
        assertEquals("STALE_SOURCE", receipt.metadataOnly().reason)
        assertEquals(reference, receipt.metadataOnly().resultReference)
        assertEquals(null, receipt.copy(reason = "a private provider log").metadataOnly().reason)
    }
}
