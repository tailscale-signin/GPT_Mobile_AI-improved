package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.LocalToolTransport
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.CurrentDateTool
import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateTurnTest {
    @Test fun suspendedPreparationCannotAdoptANewerPrivacyPolicy() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        var physicalCalls = 0
        supervisorScope {
            val preparing = async {
                privateTurnFlow(fixture.store) {
                    assertEquals(fixture.initial, requireNotNull(currentCoroutineContext()[PrivateTurn]).policy)
                    entered.complete(Unit)
                    ready.await()
                    requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]).validate()
                    physicalCalls++
                    emit(Unit)
                }.toList()
            }
            entered.await()
            fixture.state = fixture.state.copy(revision = 2, protectionRevision = 2)
            ready.complete(Unit)
            assertPolicyChanged(runCatching { preparing.await() }.exceptionOrNull())
        }
        assertEquals(0, physicalCalls)
    }

    @Test fun nativeCallbackGuardOwnsPolicyWithoutThePrivateMarker() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val guard = privateTurnFlow(fixture.store) {
            emit(requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]))
        }.single()
        assertNull(currentCoroutineContext()[PrivateTurn])
        guard.validate()
        // Restore the same categories: the protection revision still fences the old mask.
        fixture.state = fixture.state.copy(categories = fixture.state.categories - PrivacyCategory.CONTACTS, revision = 2, protectionRevision = 2)
        fixture.state = fixture.state.copy(categories = fixture.initial.categories, revision = 3, protectionRevision = 3)
        var nativeCalls = 0
        val failure = withoutConversationContext {
            runCatching {
                withContext(guard) {
                    assertNull(currentCoroutineContext()[PrivateTurn])
                    assertNull(currentCoroutineContext()[ConversationPrivacyContext])
                    guard.validate()
                    nativeCalls++
                }
            }.exceptionOrNull()
        }
        assertPolicyChanged(failure)
        assertEquals(0, nativeCalls)
    }

    @Test fun availabilityChangesNeitherUnlockPrivateTurnsNorPromoteOrdinaryTurns() = runTest {
        for (locked in listOf(false, true)) {
            withContext(ConversationPrivacyContext(if (locked) 101 else 102, locked)) {
                val fixture = Fixture(enabled = locked)
                val guard = privateTurnFlow(fixture.store) {
                    assertEquals(locked, currentCoroutineContext()[PrivateTurn] != null)
                    assertEquals(locked, PrivacyModeStore.active())
                    emit(requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]))
                }.single()
                fixture.state = fixture.state.copy(enabled = !locked, revision = 2)
                guard.validate()
                fixture.state = fixture.state.copy(enabled = locked, revision = 3)
                guard.validate()
                privateTurnFlow(fixture.store) {
                    assertEquals(locked, currentCoroutineContext()[PrivateTurn] != null)
                    assertEquals(locked, PrivacyModeStore.active())
                    emit(Unit)
                }.single()
            }
        }
    }

    @Test fun categoryAndPrivateTermRevisionChangesInvalidateTheCapturedGuard() = runTest(ConversationPrivacyContext(101, true)) {
        for (change in listOf<(PrivacyModeState) -> PrivacyModeState>(
            { it.copy(categories = it.categories - PrivacyCategory.CONTACTS, revision = 2, protectionRevision = 2) },
            { it.copy(revision = 2, protectionRevision = 2) }
        )) {
            val fixture = Fixture()
            val guard = PrivateTurn.capture(fixture.store).authority(null)
            fixture.state = change(fixture.state)
            assertPolicyChanged(runCatching { guard.validate() }.exceptionOrNull())
        }
    }

    @Test fun inheritedPermissionRevocationAndChangesDuringItsCheckStillStopDispatch() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val revoked = DispatchPolicyChangedException(listOf("profile.enabled"))
        val inherited = RequestAuthorizationGuard("selected-profile") { throw revoked }
        val guard = PrivateTurn.capture(fixture.store).authority(inherited)
        assertSame(revoked, runCatching { guard.validate() }.exceptionOrNull())

        var checks = 0
        val changing = RequestAuthorizationGuard("selected-profile") {
            checks++
            fixture.state = fixture.state.copy(revision = 2, protectionRevision = 2)
        }
        assertPolicyChanged(runCatching { PrivateTurn.capture(fixture.store).authority(changing).validate() }.exceptionOrNull())
        assertEquals(1, checks)
    }

    @Test fun cancelledOwnerCannotAuthorizeALaterNativeCallbackInAFreshCoroutine() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val captured = CompletableDeferred<RequestAuthorizationGuard>()
        val owner = launch {
            captured.complete(
                privateTurnFlow(fixture.store) {
                    emit(requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]))
                }.single()
            )
            awaitCancellation()
        }
        val guard = captured.await()
        guard.validate()
        owner.cancelAndJoin()
        assertTrue(currentCoroutineContext().isActive)
        var nativeCalls = 0
        val failure = withoutConversationContext {
            runCatching {
                withContext(guard) {
                    guard.validate()
                    nativeCalls++
                }
            }.exceptionOrNull()
        }
        assertTrue(failure is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(0, nativeCalls)
    }

    @Test fun changedPrivateTurnCannotExpandIntoExternalExecutionWhenAvailabilityIsOff() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val turn = PrivateTurn.capture(fixture.store)
        fixture.state = fixture.state.copy(enabled = false, revision = 2, protectionRevision = 2)
        val failure = withoutConversationContext {
            runCatching {
                withContext(turn) {
                    assertFalse(PrivacyModeStore.active())
                    LocalToolTransport.requireExecution(fixture.external)
                    fixture.external.execute("stale-private", buildJsonObject {})
                }
            }.exceptionOrNull()
        }
        assertPolicyChanged(failure)
        assertEquals(0, fixture.externalCalls)
    }

    @Test fun markerFreeNativeCallbackRechecksProtectionRevisionsAtThePhysicalToolBoundary() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val guard = privateTurnFlow(fixture.store) {
            emit(requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]))
        }.single()
        guard.validate()
        // Admission passed before a queued callback/event sink observed a protection change.
        fixture.state = fixture.state.copy(categories = emptySet(), revision = 2, protectionRevision = 2)
        fixture.state = fixture.state.copy(categories = fixture.initial.categories, revision = 3, protectionRevision = 3)
        val failure = withoutConversationContext {
            runCatching {
                withContext(guard) {
                    assertNull(currentCoroutineContext()[PrivateTurn])
                    assertFalse(PrivacyModeStore.active())
                    LocalToolTransport.requireExecution(fixture.external)
                    fixture.external.execute("native-queued", buildJsonObject {})
                }
            }.exceptionOrNull()
        }
        assertPolicyChanged(failure)
        assertEquals(0, fixture.externalCalls)
    }

    @Test fun capturedPrivateAllowlistDoesNotTrustTheLiveContextOrCopiedToolName() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val turn = PrivateTurn.capture(fixture.store)
        withoutConversationContext {
            withContext(turn) {
                assertFalse(PrivacyModeStore.active())
                val local = CurrentDateTool()
                LocalToolTransport.requireExecution(local)
                assertFalse(local.execute("local", buildJsonObject {}).isError)
                val failure = runCatching {
                    LocalToolTransport.requireExecution(fixture.external)
                    fixture.external.execute("forged-local", buildJsonObject {})
                }.exceptionOrNull()
                assertTrue(failure is UnverifiedServiceTransport)
            }
        }
        assertEquals(0, fixture.externalCalls)
    }

    @Test fun unchangedPrivateCallbackRetainsItsConversationWhenThePrivateMarkerIsAbsent() = runTest(ConversationPrivacyContext(101, true)) {
        val fixture = Fixture()
        val conversation = requireNotNull(currentCoroutineContext()[ConversationPrivacyContext])
        val guard = privateTurnFlow(fixture.store) {
            emit(requireNotNull(currentCoroutineContext()[RequestAuthorizationGuard]))
        }.single()
        fixture.state = fixture.state.copy(enabled = false, revision = 2)
        withoutConversationContext {
            withContext(guard + conversation) {
                assertNull(currentCoroutineContext()[PrivateTurn])
                guard.validate()
                yield()
                assertTrue(PrivacyModeStore.active())
                val local = CurrentDateTool()
                LocalToolTransport.requireExecution(local)
                assertFalse(local.execute("native-local", buildJsonObject {}).isError)
                val failure = runCatching {
                    LocalToolTransport.requireExecution(fixture.external)
                    fixture.external.execute("native-forged-local", buildJsonObject {})
                }.exceptionOrNull()
                assertTrue(failure is UnverifiedServiceTransport)
            }
        }
        assertEquals(0, fixture.externalCalls)
    }

    /** The native callback coroutine does not inherit either private context element. */
    private suspend fun <T> withoutConversationContext(block: suspend () -> T): T =
        CoroutineScope(currentCoroutineContext().minusKey(ConversationPrivacyContext).minusKey(PrivateTurn)).async { block() }.await()

    private fun assertPolicyChanged(failure: Throwable?) {
        assertTrue(failure.toString(), failure is DispatchPolicyChangedException)
        assertEquals(listOf("devicePrivacy"), (failure as DispatchPolicyChangedException).fields)
    }

    private class Fixture(enabled: Boolean = true) {
        val initial = PrivacyModeState(enabled = enabled, revision = 1)
        var state = initial
        val store = mockk<PrivacyModeStore>().also {
            every { it.snapshot() } answers { state }
            every { it.effectiveSnapshot(any()) } answers { callOriginal() }
        }
        var externalCalls = 0
        val external = object : AgentTool {
            override val definition = CurrentDateTool().definition
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                externalCalls++
                return AgentToolResult(callId, ToolResultContent.Text("External result"), false)
            }
        }
    }
}
