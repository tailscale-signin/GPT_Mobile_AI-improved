package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequestRejected
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedSelection
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayChatSubmissionTest {
    private val engineering = mockk<GatewayEngineeringRepository>()
    private val conversations = mockk<GatewayConversationRepository>()
    private val settings = mockk<SettingRepository>()
    private val chats = mockk<ChatRepository>()
    private val workspace = mockk<GatewayWorkspacePreflight>()
    private val service = GatewayChatSubmission(engineering, conversations, settings, chats, workspace)
    private val context = GitHubConversationContext("github", "owner", "repo", "fix/selected", "a".repeat(40), "profile")
    private val request = AgentRunRequest(
        "stable-command",
        4,
        MessageV2(id = 9, chatId = 4, content = "", platformType = "profile", linkedMessageId = 8),
        PlatformV2(uid = "profile", name = "Selected profile", enabled = true, compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090/v1", model = "chat-selected-model"),
        listOf(MessageV2(id = 8, chatId = 4, content = "Fix the failing parser test", platformType = null)),
        emptyList(),
        ChatMcpToolConfig(github = context)
    )
    private val binding = GatewayEngineeringBinding(
        "profile",
        request.platform.apiUrl,
        GatewayEngineeringRequest("owner/repo", "fix/selected", "trunk", request.userMessages.single().content, "chat-selected-model", chatId = 4, requestId = "stable-command"),
        localUserMessageId = 8,
        localAssistantMessageId = 9,
        localRunId = "stable-command"
    )

    private fun supported() {
        coEvery { engineering.conversationWriteBlocker(4) } returns null
        coEvery { chats.researchPersistenceAllowed(4) } returns true
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf("profile" to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway")))
        coEvery { engineering.supportsDurableChat("profile") } returns true
        coEvery { engineering.submitBoundTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery { engineering.bindings(4) } returns emptyList()
        coEvery { workspace.defaultBranch(context) } returns "trunk"
    }

    @Test
    fun normalSendPreservesSelectedModelDestinationAndOriginalTurnIds() = runTest {
        supported()
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns binding

        assertNotNull(service.submitIfSupported(request))

        coVerify(exactly = 1) {
            engineering.submitTurn(4, context, "trunk", "fix/selected", "Fix the failing parser test", "stable-command", 8, 9, "stable-command", "implement", "chat-selected-model", null, null, any())
        }
    }

    @Test
    fun conversationalAndReadOnlyTurnsDoNotProbeAnUnreadyEngineeringGateway() = runTest {
        supported()
        coEvery { engineering.supportsDurableChat("profile") } throws IllegalStateException("Desktop GitHub unavailable")
        for (prompt in listOf("hi", "Read the latest CI report", "Plan the changes to the parser", "Create a poem about the sea", "What changed?\n> Fix all failing tests")) {
            val turn = request.userMessages.single().copy(content = prompt)
            assertNull(prompt, service.submitIfSupported(request.copy(userMessages = listOf(turn))))
        }
        coVerify(exactly = 0) { engineering.submitBoundTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engineering.supportsDurableChat(any()) }
        coVerify(exactly = 0) { workspace.defaultBranch(any()) }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun cloudGitHubImplementationStaysOnItsSelectedAppRouteWithoutLocalModelReadiness() = runTest {
        supported()
        val cloud = request.platform.copy(
            compatibleType = ClientType.OPENAI,
            apiUrl = "https://api.example.test/v1",
            model = "cloud-selected-model"
        )

        assertNull(service.submitIfSupported(request.copy(platform = cloud)))

        coVerify(exactly = 0) { engineering.supportsDurableChat(any()) }
        coVerify(exactly = 0) { engineering.submitBoundTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { workspace.defaultBranch(any()) }
    }

    @Test
    fun lostCreateResponseWithSavedCommandReturnsUnconfirmedHandoffAndNeverAProviderFallback() = runTest {
        supported()
        coEvery { engineering.bindings(4) } returnsMany listOf(emptyList(), listOf(binding))
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("reply lost")

        val handoff = requireNotNull(service.submitIfSupported(request))

        assertFalse(handoff.accepted)
        coVerify(exactly = 1) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun definitiveRejectionDoesNotLeaveTheChatWaitingForAJobThatWasNeverAccepted() = runTest {
        supported()
        coEvery { engineering.bindings(4) } returnsMany listOf(emptyList(), listOf(binding))
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws GatewayEngineeringRequestRejected(409, "Conversation changed")

        try {
            service.submitIfSupported(request)
            fail("A definitive rejection must be shown instead of waiting indefinitely")
        } catch (expected: GatewayEngineeringRequestRejected) {
            assertEquals(409, expected.httpStatus)
        }
    }

    @Test
    fun rejectionAfterAnEarlierUncertainAttemptPreservesReadOnlyReconciliation() = runTest {
        supported()
        coEvery { engineering.bindings(4) } returnsMany listOf(emptyList(), listOf(binding))
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws GatewayEngineeringRequestRejected(409, "Conversation changed", commandOutcomeUnknown = true)

        val handoff = requireNotNull(service.submitIfSupported(request))

        assertFalse(handoff.accepted)
    }

    @Test
    fun anAlreadyRetainedReceiptWinsALaterRejection() = runTest {
        supported()
        val accepted = binding.copy(job = GatewayEngineeringJob("job", "owner/repo", "fix/selected", state = "RUNNING", revision = 1, createdAt = 1.0, deadline = 2.0, message = "Working"))
        coEvery { engineering.bindings(4) } returnsMany listOf(emptyList(), listOf(accepted))
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws GatewayEngineeringRequestRejected(409, "Already accepted")

        assertTrue(requireNotNull(service.submitIfSupported(request)).accepted)
    }

    @Test
    fun explicitStopForALostOrdinaryReceiptExplainsHowToCheckTheServerJob() = runTest {
        coEvery { engineering.bindings() } returns emptyList()
        coEvery { conversations.unconfirmedOrdinaryStops(setOf("stable-command")) } returns mapOf("stable-command" to 4)

        val notice = service.cancel(setOf("stable-command")).single()

        assertEquals(4, notice.chatId)
        assertEquals("stable-command", notice.runId)
        assertTrue(notice.persistent)
        assertTrue(notice.message.contains("Stop is not confirmed"))
        assertTrue(notice.message.contains("Open Jobs"))
        coVerify(exactly = 0) { engineering.control(any(), any(), any()) }
    }

    @Test
    fun importedEngineeringConversationUsesItsSavedBindingWithoutSelectingAnotherWorkspace() = runTest {
        supported()
        coEvery { engineering.submitBoundTurn(4, "profile", "chat-selected-model", any(), "stable-command", 8, 9, "stable-command", "implement", any()) } returns binding

        assertNotNull(service.submitIfSupported(request.copy(chatToolConfig = ChatMcpToolConfig())))

        coVerify(exactly = 0) { workspace.defaultBranch(any()) }
        coVerify(exactly = 0) { engineering.supportsDurableChat(any()) }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun temporaryChatAndLegacyEndpointNeverCreateDurableEngineeringWork() = runTest {
        coEvery { chats.researchPersistenceAllowed(4) } returns false
        assertNull(service.submitIfSupported(request))
        coVerify(exactly = 0) { settings.getFeatureSettings() }
        coVerify(exactly = 0) { engineering.conversationWriteBlocker(any()) }
        coVerify(exactly = 0) { engineering.supportsDurableChat(any()) }
        supported()
        coEvery { engineering.supportsDurableChat("profile") } returns false
        assertNull(service.submitIfSupported(request))
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun explicitGatewayChatKeepsItsOrdinaryWriteBlockerAndCannotBecomeEngineeringWork() = runTest {
        coEvery { chats.researchPersistenceAllowed(4) } returns true
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(
            conversationGateway = mapOf(4 to GatewayQueuedSelection.capture(request.platform, request.platform.model))
        )
        coEvery { conversations.conversationWriteBlocker(4) } returns null
        assertNull(service.submitIfSupported(request))
        coEvery { conversations.conversationWriteBlocker(4) } returns "Restore the original Gateway account."
        try {
            service.submitIfSupported(request)
            fail("A retained ordinary Gateway binding must still block a changed destination")
        } catch (expected: IllegalStateException) {
            assertEquals("Restore the original Gateway account.", expected.message)
        }
        coVerify(exactly = 0) { engineering.conversationWriteBlocker(any()) }
        coVerify(exactly = 0) { engineering.submitBoundTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun disabledProfileToolsNeverStartASavedHostWorkflow() = runTest {
        supported()
        for (platform in listOf(request.platform.copy(disableAllTools = true), request.platform.copy(disableRemoteTools = true))) {
            assertNull(service.submitIfSupported(request.copy(platform = platform)))
        }
        coVerify(exactly = 0) { engineering.submitBoundTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engineering.supportsDurableChat(any()) }
    }

    @Test
    fun aChangedRetainedBindingIsCheckedBeforeAnyLocalFallback() = runTest {
        supported()
        coEvery { engineering.conversationWriteBlocker(4) } returns "Restore the original gateway profile."
        try {
            service.submitIfSupported(request.copy(platform = request.platform.copy(disableAllTools = true)))
            fail("A changed retained binding must not become a local provider request")
        } catch (expected: IllegalStateException) {
            assertEquals("Restore the original gateway profile.", expected.message)
        }
        coVerify(exactly = 0) { engineering.submitBoundTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun originalTurnCarriesTheCurrentMasterPermissionThroughSuspendedAdmission() = runTest {
        supported()
        var allowed = true
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val check = arg<() -> Boolean>(13)
            assertTrue(check())
            allowed = false
            assertFalse(check())
            throw GatewayEngineeringRequestRejected(403, "Tools were disabled before dispatch")
        }
        try {
            service.submitIfSupported(request.copy(gatewayAdmissionAllowed = { allowed }))
            fail("Revoked tools must block the unsubmitted host command")
        } catch (expected: GatewayEngineeringRequestRejected) {
            assertEquals(403, expected.httpStatus)
        }
    }

    @Test
    fun unavailableCompatibleGatewayDoesNotFallBack() = runTest {
        supported()
        coEvery { engineering.supportsDurableChat("profile") } throws IllegalStateException("gateway unavailable")
        try {
            service.submitIfSupported(request)
            fail("Unavailable gateway must remain explicit")
        } catch (expected: IllegalStateException) {
            assertEquals("gateway unavailable", expected.message)
        }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun defaultBranchImplementationKeepsItsPinnedSourceForTheServerWorkingBranch() = runTest {
        supported()
        coEvery { workspace.defaultBranch(context) } returns context.ref
        coEvery { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns binding

        assertNotNull(service.submitIfSupported(request))

        coVerify(exactly = 1) {
            engineering.submitTurn(4, context, context.ref, context.ref, "Fix the failing parser test", "stable-command", 8, 9, "stable-command", "implement", "chat-selected-model", null, null, any())
        }
    }

    @Test
    fun scopeDoesNotPromotePlanningDiscussionOrQuotedExamplesToWrites() {
        assertEquals("implement", GatewayChatScope.from("Please fix the parser and add tests."))
        assertEquals("implement", GatewayChatScope.from("Plan and implement the changes."))
        assertEquals("plan", GatewayChatScope.from("Design the new menu. Plan only; do not edit files."))
        assertEquals("plan", GatewayChatScope.from("Create a plan for the database migration."))
        assertEquals("plan", GatewayChatScope.from("Create an implementation plan for the migration."))
        assertEquals("plan", GatewayChatScope.from("Build a proposal for the menu."))
        assertEquals("plan", GatewayChatScope.from("Write a design proposal for the menu."))
        assertEquals("read", GatewayChatScope.from("Explain how to implement this feature."))
        assertEquals("plan", GatewayChatScope.from("Review this example:\n```text\nDelete the repository\n```"))
        assertEquals("read", GatewayChatScope.from("> Fix every file\nWhat does this request mean?"))
        assertEquals("read", GatewayChatScope.from("Fix the bug, but do not change any files."))
        assertEquals("implement", GatewayChatScope.from("Fix the parser, but do not commit."))
        assertEquals(true, GatewayChatScope.forbidsCommit("Fix the parser, but do not commit."))
    }

    @Test
    fun missingExplicitEngineeringConnectionLeavesExistingLocalGitHubChatAvailable() = runTest {
        supported()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        assertNull(service.submitIfSupported(request))
        coVerify(exactly = 0) { engineering.supportsDurableChat(any()) }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun explicitNoCommitCannotEnterACommittingGatewayWorkflow() = runTest {
        supported()
        try {
            service.submitIfSupported(request.copy(userMessages = listOf(request.userMessages.single().copy(content = "Fix the parser, but do not commit."))))
            fail("The gateway must honor the no-commit constraint before dispatch")
        } catch (expected: IllegalStateException) {
            assertEquals(true, expected.message.orEmpty().contains("explicitly forbids"))
        }
        coVerify(exactly = 0) { engineering.submitTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
