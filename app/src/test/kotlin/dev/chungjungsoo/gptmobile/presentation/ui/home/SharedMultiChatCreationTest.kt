package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.NewChatType
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeState
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProviderVerifier
import dev.chungjungsoo.gptmobile.data.privacy.PrivacySettingsSaveState
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SharedMultiChatCreationTest {
    @Test fun creatingFromTheFirstMessageKeepsItAsAnUnsentDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profiles = listOf(PlatformV2(uid = "p", name = "Selected", model = "test"))
            val settings = proxy<SettingRepository> { name, _ ->
                when (name) {
                    "observePlatformV2s" -> flowOf(profiles)
                    "fetchPlatformV2s" -> profiles
                    "observeFeatureSettings" -> flowOf(AppFeatureSettings())
                    "providerActivationRequired" -> false
                    "getFeatureSettings", "mutateFeatureSettings" -> AppFeatureSettings()
                    else -> error("Unexpected setting operation: $name")
                }
            }
            val drafts = mutableListOf<String?>()
            val chats = proxy<ChatRepository> { name, arguments ->
                check(name == "createDraftChat") { "Creating a room dispatched $name" }
                drafts += arguments[4] as String?
                ChatRoomV2(id = 12, title = "New Chat", enabledPlatform = listOf("p"))
            }
            val state = SavedStateHandle(mapOf("new_chat_type" to "SINGLE", "new_chat_uids" to listOf("p")))
            val model = NewChatViewModel(state, settings, chats, mockk())
            store.put("new-chat", model)
            val task = "Diagnose this issue with my shader."
            model.editFirstMessage(task)
            model.editFirstMessage("x".repeat(8193))
            var route = ""
            model.start { route = it }
            runCurrent()
            assertEquals(listOf(task), drafts)
            assertTrue(route.startsWith("chat_room/12?"))
            model.start { route = it }
            assertEquals(1, drafts.size)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun multiChatCreatesOneRoomContainingEverySelectedProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profiles = listOf(PlatformV2(uid = "a", name = "Alpha"), PlatformV2(uid = "b", name = "Beta"))
            val created = mutableListOf<ChatRoomV2>()
            var features = AppFeatureSettings()
            val settings = proxy<SettingRepository> { name, args ->
                when (name) {
                    "observePlatformV2s" -> flowOf(profiles)
                    "observeFeatureSettings" -> flowOf(features)
                    "fetchPlatformV2s" -> profiles
                    "providerActivationRequired" -> false
                    "mutateFeatureSettings" -> {
                        @Suppress("UNCHECKED_CAST")
                        val transform = args[0] as (AppFeatureSettings) -> AppFeatureSettings
                        features = transform(features)
                        features
                    }
                    else -> error("Unexpected settings operation: $name")
                }
            }
            val chats = proxy<ChatRepository> { name, args ->
                check(name == "createDraftChat") { "Unexpected chat operation: $name" }
                @Suppress("UNCHECKED_CAST")
                val uids = args[0] as List<String>
                ChatRoomV2(id = 42, title = args[2] as String, enabledPlatform = uids, conversationMode = args[1] as String)
                    .also(created::add)
            }
            val availability = mockk<GatewayAvailabilityRepository>()
            val viewModel = NewChatViewModel(
                SavedStateHandle(mapOf("new_chat_type" to "MULTI_CHAT", "new_chat_uids" to listOf("a", "b"))),
                settings,
                chats,
                availability
            )
            store.put("new-chat", viewModel)
            var route = ""
            viewModel.start { route = it }
            runCurrent()
            assertEquals(1, created.size)
            verify(exactly = 0) { availability.observe(any()) }
            assertEquals(listOf("a", "b"), created.single().enabledPlatform)
            assertEquals(ConversationMode.STANDARD, created.single().conversationMode)
            assertEquals("chat_room/42?enabled=a,b&mode=STANDARD", route)
            assertTrue(features.multiChatWorkspaces.isEmpty())
            viewModel.start { route = it }
            runCurrent()
            assertEquals(1, created.size)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun githubSelectionOpensWorkspaceWithoutCreatingAnUnscopedChat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "coder", name = "Coder")
            val features = AppFeatureSettings().withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB, true)
            val settings = proxy<SettingRepository> { name, _ ->
                when (name) {
                    "observePlatformV2s" -> flowOf(listOf(profile))
                    "observeFeatureSettings" -> flowOf(features)
                    "fetchPlatformV2s" -> listOf(profile)
                    "getFeatureSettings" -> features
                    "providerActivationRequired" -> false
                    else -> error("Unexpected settings operation: $name")
                }
            }
            val chats = proxy<ChatRepository> { name, _ -> error("Chat must be scoped to a repository before $name") }
            val availability = mockk<GatewayAvailabilityRepository>()
            val viewModel = NewChatViewModel(SavedStateHandle(mapOf("new_chat_type" to "GITHUB", "new_chat_uids" to listOf("coder"))), settings, chats, availability)
            store.put("github-chat", viewModel)
            var route = ""
            viewModel.start { route = it }
            runCurrent()
            assertEquals("github_chat/coder", route)
            verify(exactly = 0) { availability.observe(any()) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacyEnabledPluginModesOpenLocallyWhileProviderVerificationIsPending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            for (type in listOf(NewChatType.GITHUB, NewChatType.RESEARCH)) {
                val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
                var features = pluginCreationFeatures(profile.uid)
                val fixture = privacyFixture(listOf(profile), listOf(profile.uid), type, features)
                store.put(type.name, fixture.model)
                val reply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
                coEvery { fixture.verifier.verify(profile) } coAnswers { PrivacyProviderVerifier.Result(reply.await()) }
                coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
                coEvery { fixture.settings.providerActivationRequired(any()) } returns false
                coEvery { fixture.settings.mutateFeatureSettings(any()) } answers {
                    firstArg<(AppFeatureSettings) -> AppFeatureSettings>().invoke(features).also { features = it }
                }
                coEvery { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) } returns
                    ChatRoomV2(id = 64, title = "Research Chat", enabledPlatform = listOf(profile.uid))
                val refresh = backgroundScope.launch { fixture.model.checkPrivacyProfiles(listOf(profile)) }
                runCurrent()
                assertEquals(PrivacyProfileEligibility.Status.CHECKING, fixture.model.privacyDecision(profile).status)
                fixture.model.editFirstMessage("Keep this research question in the local draft.")

                var route = ""
                fixture.model.start { route = it }
                runCurrent()

                assertTrue(fixture.model.privacyState.value.enabled)
                assertTrue(fixture.model.privacyChecking.value)
                assertFalse(reply.isCompleted)
                assertTrue(fixture.model.error.value.isNullOrEmpty())
                coVerify(exactly = 1) { fixture.verifier.verify(profile) }
                if (type == NewChatType.GITHUB) {
                    assertEquals("github_chat/private", route)
                    coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) }
                } else {
                    assertEquals("chat_room/64?enabled=private&mode=STANDARD", route)
                    coVerify(exactly = 1) {
                        fixture.chats.createDraftChat(listOf(profile.uid), ConversationMode.STANDARD, "Research Chat", profile.uid, "Keep this research question in the local draft.")
                    }
                    assertTrue(64 in features.researchChatIds)
                    assertTrue(features.conversationResearch.getValue(64).researchToggle)
                }
                refresh.cancel()
                refresh.join()
            }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacyEnabledPluginModesStillRequireToolPermissionsAndProviderActivation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            for (type in listOf(NewChatType.GITHUB, NewChatType.RESEARCH)) {
                for (activationRequired in listOf(false, true)) {
                    val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
                    val allowed = pluginCreationFeatures(profile.uid)
                    val features = if (activationRequired) allowed else allowed.withToolPluginEnabled(requireNotNull(type.requiredPluginId), false)
                    val fixture = privacyFixture(listOf(profile), listOf(profile.uid), type, features)
                    store.put("${type.name}-$activationRequired", fixture.model)
                    coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
                    coEvery { fixture.settings.providerActivationRequired(any()) } returns activationRequired

                    var opened = false
                    fixture.model.start { opened = true }
                    runCurrent()

                    assertFalse(opened)
                    assertFalse(fixture.model.creating.value)
                    val reason = fixture.model.error.value.orEmpty()
                    assertTrue(reason, if (activationRequired) reason.contains("data policy") else reason.contains("plugin", ignoreCase = true))
                    coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) }
                    coVerify(exactly = 0) { fixture.verifier.verify(any()) }
                }
            }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacyEnabledResearchStillRequiresProfileNetworkPermission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model", disableRemoteTools = true)
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid), NewChatType.RESEARCH, pluginCreationFeatures(profile.uid))
            store.put("research", fixture.model)
            coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { fixture.settings.providerActivationRequired(any()) } returns false

            var opened = false
            fixture.model.start { opened = true }
            runCurrent()

            assertFalse(opened)
            assertTrue(fixture.model.error.value.orEmpty().contains("Network tools are disabled"))
            coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacyEnabledPluginModesRejectPrivacyChangesDuringCreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            for (type in listOf(NewChatType.GITHUB, NewChatType.RESEARCH)) {
                val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
                val fixture = privacyFixture(listOf(profile), listOf(profile.uid), type, pluginCreationFeatures(profile.uid))
                store.put(type.name, fixture.model)
                val approval = CompletableDeferred<Boolean>()
                coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
                coEvery { fixture.settings.providerActivationRequired(any()) } coAnswers { approval.await() }
                var opened = false
                fixture.model.start { opened = true }
                runCurrent()
                assertTrue(fixture.model.creating.value)

                fixture.model.setPrivacyMode(false)
                fixture.model.setPrivacyMode(true)
                approval.complete(false)
                runCurrent()

                assertFalse(opened)
                assertTrue(fixture.model.error.value.orEmpty().contains("Privacy settings changed"))
                coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) }
            }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun suggestionsRemainSelectedAcrossPrivacyChecks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val settings = mockk<SettingRepository>()
            every { settings.observePlatformV2s() } returns flowOf(listOf(profile))
            every { settings.observeFeatureSettings() } returns flowOf(AppFeatureSettings())
            val state = MutableStateFlow(PrivacyModeState(enabled = true))
            val verifier = mockk<PrivacyProviderVerifier>()
            val privacy = mockk<PrivacyModeStore>()
            every { privacy.state } returns state
            every { privacy.updates } returns MutableStateFlow(PrivacySettingsSaveState())
            every { privacy.snapshot() } returns state.value
            every { privacy.verifier } returns verifier
            val viewModel = NewChatViewModel(SavedStateHandle(), settings, mockk(), mockk(), privacyMode = privacy)
            store.put("suggestion", viewModel)
            val observer = backgroundScope.launch { viewModel.profiles.collect {} }
            runCurrent()
            viewModel.chooseSuggestedProfile(profile.uid, false)
            assertEquals(listOf(profile.uid), viewModel.selectedUids.value)
            coEvery { verifier.verify(profile) } returns PrivacyProviderVerifier.Result(PrivacyProfileEligibility.Decision(true, "Verified"))
            viewModel.checkPrivacyProfiles(listOf(profile))
            viewModel.chooseSuggestedProfile(profile.uid, false)
            assertEquals(listOf(profile.uid), viewModel.selectedUids.value)
            coEvery { verifier.verify(profile) } returns PrivacyProviderVerifier.Result(PrivacyProfileEligibility.Decision(false, "Approval expired"))
            viewModel.checkPrivacyProfiles(listOf(profile))
            viewModel.chooseSuggestedProfile(profile.uid, false)
            assertEquals(listOf(profile.uid), viewModel.selectedUids.value)
            assertTrue(viewModel.error.value.isNullOrEmpty())
            observer.cancel()
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacyChecksShowPendingStateAndPublishEachResultWithBoundedConcurrency() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profiles = (1..6).map { PlatformV2(uid = "profile-$it", name = "Profile $it", model = "model") }
            val fixture = privacyFixture(profiles)
            store.put("privacy", fixture.model)
            val replies = profiles.associate { it.uid to CompletableDeferred<PrivacyProfileEligibility.Decision>() }
            var active = 0
            var maximum = 0
            coEvery { fixture.verifier.verify(any()) } coAnswers {
                active++
                maximum = maxOf(maximum, active)
                try {
                    PrivacyProviderVerifier.Result(replies.getValue(firstArg<PlatformV2>().uid).await())
                } finally {
                    active--
                }
            }
            val refresh = launch { fixture.model.checkPrivacyProfiles(profiles) }
            runCurrent()
            assertTrue(fixture.model.privacyChecking.value)
            assertEquals(4, active)
            profiles.forEach {
                assertEquals(PrivacyProfileEligibility.Status.CHECKING, fixture.model.privacyDecision(it).status)
                assertFalse(fixture.model.privacyDecision(it).eligible)
            }
            replies.getValue(profiles.first().uid).complete(PrivacyProfileEligibility.Decision(true, "Verified"))
            runCurrent()
            assertTrue(fixture.model.privacyDecision(profiles.first()).eligible)
            assertEquals(PrivacyProfileEligibility.Status.CHECKING, fixture.model.privacyDecision(profiles.last()).status)
            assertTrue(fixture.model.privacyChecking.value)
            replies.values.forEach { it.complete(PrivacyProfileEligibility.Decision(true, "Verified")) }
            refresh.join()
            assertEquals(4, maximum)
            assertFalse(fixture.model.privacyChecking.value)
            assertTrue(profiles.all { it.enabled && fixture.model.privacyDecision(it).eligible })
            coVerify(exactly = 0) { fixture.settings.updatePlatformV2(any()) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun supersededPrivacyCheckCannotOverwriteNewerRejection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile))
            store.put("privacy", fixture.model)
            val oldReply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
            var calls = 0
            coEvery { fixture.verifier.verify(profile) } coAnswers {
                calls++
                PrivacyProviderVerifier.Result(
                    if (calls == 1) oldReply.await() else PrivacyProfileEligibility.Decision(false, "Account evidence expired")
                )
            }
            val old = launch { fixture.model.checkPrivacyProfiles(listOf(profile)) }
            runCurrent()
            fixture.model.checkPrivacyProfiles(listOf(profile))
            oldReply.complete(PrivacyProfileEligibility.Decision(true, "Old verification"))
            old.join()
            assertFalse(fixture.model.privacyDecision(profile).eligible)
            assertEquals("Account evidence expired", fixture.model.privacyDecision(profile).explanation)
            assertFalse(fixture.model.privacyChecking.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledPrivacyCheckClearsPendingStateWithoutChangingSavedSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid))
            store.put("privacy", fixture.model)
            val reply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
            coEvery { fixture.verifier.verify(profile) } coAnswers { PrivacyProviderVerifier.Result(reply.await()) }
            val refresh = launch { fixture.model.checkPrivacyProfiles(listOf(profile)) }
            runCurrent()
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            assertEquals(PrivacyProfileEligibility.Status.CHECKING, fixture.model.privacyDecision(profile).status)
            refresh.cancel()
            refresh.join()
            assertFalse(fixture.model.privacyChecking.value)
            assertFalse(fixture.model.privacyDecision(profile).eligible)
            assertEquals(PrivacyProfileEligibility.Status.NOT_VERIFIED, fixture.model.privacyDecision(profile).status)
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            coVerify(exactly = 0) { fixture.settings.updatePlatformV2(any()) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacyToggleInvalidatesInFlightSuccessEvenWhenSwitchedBackOn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile))
            store.put("privacy", fixture.model)
            val reply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
            coEvery { fixture.verifier.verify(profile) } coAnswers { PrivacyProviderVerifier.Result(reply.await()) }
            val refresh = launch { fixture.model.checkPrivacyProfiles(listOf(profile)) }
            runCurrent()
            fixture.model.setPrivacyMode(false)
            fixture.model.setPrivacyMode(true)
            reply.complete(PrivacyProfileEligibility.Decision(true, "Old verification"))
            refresh.join()
            assertTrue(fixture.model.privacyState.value.enabled)
            assertTrue(fixture.model.checkedProfiles.value.isEmpty())
            assertFalse(fixture.model.privacyDecision(profile).eligible)
            assertFalse(fixture.model.privacyChecking.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun privacySaveStatesKeepProfilesSelectableAndToggleQueuesPersistence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid))
            store.put("privacy", fixture.model)
            val observer = backgroundScope.launch { fixture.model.profiles.collect {} }
            runCurrent()
            every { fixture.privacy.requestEnabled(any()) } answers {
                fixture.updates.value = PrivacySettingsSaveState(pending = 1)
            }
            every { fixture.privacy.retrySaving() } returns Unit

            fixture.model.setPrivacyMode(false)
            verify(exactly = 1) { fixture.privacy.requestEnabled(false) }
            assertTrue(fixture.model.privacyUpdates.value.saving)
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            fixture.model.toggleProfile(profile.uid)
            assertTrue(fixture.model.selectedUids.value.isEmpty())
            fixture.model.toggleProfile(profile.uid)
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)

            fixture.updates.value = PrivacySettingsSaveState(failed = true)
            assertTrue(fixture.model.privacyUpdates.value.failed)
            assertFalse(fixture.model.privacyUpdates.value.saving)
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            fixture.model.toggleProfile(profile.uid)
            assertTrue(fixture.model.selectedUids.value.isEmpty())
            fixture.model.toggleProfile(profile.uid)
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            fixture.model.retryPrivacySave()
            verify(exactly = 1) { fixture.privacy.retrySaving() }

            fixture.model.setPrivacyMode(true)
            verify(exactly = 1) { fixture.privacy.requestEnabled(true) }
            verify(exactly = 0) { fixture.privacy.setEnabled(any()) }
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            assertTrue(fixture.model.error.value.isNullOrEmpty())
            observer.cancel()
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun startingChatDoesNotWaitForPendingPrivacyVerification() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid))
            store.put("privacy", fixture.model)
            val reply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
            coEvery { fixture.verifier.verify(profile) } coAnswers { PrivacyProviderVerifier.Result(reply.await()) }
            coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { fixture.settings.providerActivationRequired(any()) } returns false
            coEvery { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) } returns
                ChatRoomV2(id = 42, title = "New Chat", enabledPlatform = listOf(profile.uid))
            coEvery { fixture.settings.mutateFeatureSettings(any()) } returns AppFeatureSettings()

            val refresh = backgroundScope.launch { fixture.model.checkPrivacyProfiles(listOf(profile)) }
            runCurrent()
            assertTrue(fixture.model.privacyChecking.value)
            assertEquals(PrivacyProfileEligibility.Status.CHECKING, fixture.model.privacyDecision(profile).status)

            var route = ""
            fixture.model.start { route = it }
            runCurrent()

            assertTrue(route.startsWith("chat_room/42?"))
            assertFalse(fixture.model.creating.value)
            assertTrue(fixture.model.privacyChecking.value)
            assertFalse(reply.isCompleted)
            assertEquals(listOf(profile.uid), fixture.model.selectedUids.value)
            assertTrue(fixture.model.error.value.isNullOrEmpty())
            coVerify(exactly = 1) { fixture.verifier.verify(profile) }
            coVerify(exactly = 1) { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) }
            refresh.cancel()
            refresh.join()
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun startingChatWithUnverifiedProfileSkipsPrivacyAdmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid))
            store.put("privacy", fixture.model)
            coEvery { fixture.verifier.verify(profile) } returns PrivacyProviderVerifier.Result(PrivacyProfileEligibility.Decision(true, "Verified"))
            fixture.model.checkPrivacyProfiles(listOf(profile))
            assertTrue(fixture.model.privacyDecision(profile).eligible)
            coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { fixture.verifier.verify(profile) } returns PrivacyProviderVerifier.Result(PrivacyProfileEligibility.Decision(false, "Account evidence revoked"))
            coEvery { fixture.settings.providerActivationRequired(any()) } returns false
            coEvery { fixture.chats.createDraftChat(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
            coEvery { fixture.settings.mutateFeatureSettings(any()) } returns AppFeatureSettings()
            var created = false
            fixture.model.start { created = true }
            runCurrent()
            assertTrue(created)
            coVerify(exactly = 1) { fixture.verifier.verify(profile) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changingProfileDuringPrivacyVerificationPreventsChatCreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "initial-model")
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid))
            store.put("privacy", fixture.model)
            var saved = profile
            val reply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
            coEvery { fixture.settings.fetchPlatformV2s() } answers { listOf(saved) }
            coEvery { fixture.settings.providerActivationRequired(any()) } coAnswers {
                reply.await()
                false
            }
            var created = false
            fixture.model.start { created = true }
            runCurrent()
            saved = profile.copy(model = "changed-model")
            reply.complete(PrivacyProfileEligibility.Decision(true, "Initial model verified"))
            runCurrent()
            assertFalse(created)
            assertTrue(fixture.model.error.value.orEmpty().contains("profile changed while checking"))
            coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun switchingPrivacyOffAndBackOnWhileStartingPreventsChatCreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profile = PlatformV2(uid = "private", name = "Private provider", model = "model")
            val fixture = privacyFixture(listOf(profile), listOf(profile.uid))
            store.put("privacy", fixture.model)
            val reply = CompletableDeferred<PrivacyProfileEligibility.Decision>()
            coEvery { fixture.settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { fixture.settings.providerActivationRequired(any()) } coAnswers {
                reply.await()
                false
            }
            var created = false
            fixture.model.start { created = true }
            runCurrent()
            fixture.model.setPrivacyMode(false)
            fixture.model.setPrivacyMode(true)
            reply.complete(PrivacyProfileEligibility.Decision(true, "Old verification"))
            runCurrent()
            assertFalse(created)
            assertTrue(fixture.model.error.value.orEmpty().contains("Privacy settings changed"))
            coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private data class PrivacyFixture(
        val model: NewChatViewModel,
        val verifier: PrivacyProviderVerifier,
        val settings: SettingRepository,
        val chats: ChatRepository,
        val privacy: PrivacyModeStore,
        val updates: MutableStateFlow<PrivacySettingsSaveState>
    )

    private fun pluginCreationFeatures(profileUid: String): AppFeatureSettings = AppFeatureSettings()
        .withToolPluginEnabled(ToolPluginId.GITHUB, true)
        .withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true)
        .withProfileToolPluginEnabled(profileUid, ToolPluginId.DEEP_RESEARCH, true)

    private fun privacyFixture(
        profiles: List<PlatformV2>,
        selection: List<String> = emptyList(),
        type: NewChatType = NewChatType.SINGLE,
        features: AppFeatureSettings = AppFeatureSettings()
    ): PrivacyFixture {
        val settings = mockk<SettingRepository>()
        every { settings.observePlatformV2s() } returns flowOf(profiles)
        every { settings.observeFeatureSettings() } returns flowOf(features)
        coEvery { settings.getFeatureSettings() } returns features
        val state = MutableStateFlow(PrivacyModeState(enabled = true))
        val updates = MutableStateFlow(PrivacySettingsSaveState())
        val verifier = mockk<PrivacyProviderVerifier>()
        val privacy = mockk<PrivacyModeStore>()
        every { privacy.state } returns state
        every { privacy.updates } returns updates
        every { privacy.snapshot() } answers { state.value }
        every { privacy.verifier } returns verifier
        every { privacy.requestEnabled(any()) } answers { state.value = state.value.copy(enabled = firstArg(), revision = state.value.revision + 1) }
        val chats = mockk<ChatRepository>()
        val model = NewChatViewModel(SavedStateHandle(mapOf("new_chat_type" to type.name, "new_chat_uids" to selection)), settings, chats, mockk(), privacyMode = privacy)
        return PrivacyFixture(model, verifier, settings, chats, privacy, updates)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T
}
