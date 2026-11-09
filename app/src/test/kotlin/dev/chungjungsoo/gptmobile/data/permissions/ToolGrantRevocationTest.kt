package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolGrantRevocationTest {
    @Test fun queuedDiskWorkCannotDelayPermanentScopedProviderOrFreeConsentDenial() = runTest {
        val fixture = Fixture()
        fixture.trust.allowProvider(fixture.connection)
        fixture.trust.allowScoped(fixture.connection, "write", fixture.scope)
        fixture.consent.grant("profile", fixture.toolId)
        val oldRevision = fixture.consent.revision()
        val occupied = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val occupying = async {
            ToolGrantPersistence.mutate {
                occupied.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        occupied.await()
        try {
            val prepared = fixture.prepare()
            // No dispatcher has run the prepared write yet, but every grant lookup is denied.
            assertFalse(fixture.trust.allows(fixture.connection, "write"))
            assertFalse(fixture.trust.allowsScoped(fixture.connection, "write", fixture.scope))
            assertFalse(fixture.consent.isGranted("profile", fixture.toolId))
            assertTrue(fixture.consent.revision() > oldRevision)
            assertTrue(fixture.trust.grants().isNotEmpty()) // old disk entries still exist
            val committing = async { prepared.commit() }
            runCurrent()
            assertFalse(committing.isCompleted)
            release.countDown()
            occupying.await()
            committing.await()
            assertFalse(ToolTrustStore(fixture.context).allows(fixture.connection, "write"))
            assertFalse(FreeModelToolConsentStore(fixture.context).isGranted("profile", fixture.toolId))
            fixture.trust.allow(fixture.connection, "write")
            assertTrue(fixture.trust.allows(fixture.connection, "write"))
        } finally {
            release.countDown()
        }
    }

    @Test fun failedDiskRevocationStaysDeniedAcrossStoreReadersUntilSuccessfulRetry() = runTest {
        val fixture = Fixture()
        fixture.trust.allow(fixture.connection, "write")
        fixture.consent.grant("profile", fixture.toolId)
        fixture.context.trust.fail = true
        assertTrue(runCatching { fixture.prepare().commit() }.isFailure)
        assertFalse(fixture.trust.allows(fixture.connection, "write"))
        assertFalse(ToolTrustStore(fixture.context).allows(fixture.connection, "write"))
        assertFalse(fixture.consent.isGranted("profile", fixture.toolId))
        assertTrue(runCatching { fixture.trust.allow(fixture.connection, "write") }.isFailure)
        fixture.context.trust.fail = false
        fixture.prepare().commit()
        fixture.trust.allow(fixture.connection, "write")
        assertTrue(fixture.trust.allows(fixture.connection, "write"))
    }

    @Test fun denialRemainsAcrossSuspendingCredentialActivationAndFailedActivation() = runTest {
        val fixture = Fixture()
        fixture.trust.allow(fixture.connection, "write")
        val activated = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val replacement = async {
            fixture.prepare().activate {
                assertTrue(fixture.trust.grants().isEmpty())
                activated.complete(Unit)
                finish.await()
                "saved"
            }
        }
        activated.await()
        assertFalse(fixture.trust.allows(fixture.connection, "write"))
        assertTrue(runCatching { fixture.trust.allowProvider(fixture.connection) }.isFailure)
        finish.complete(Unit)
        assertEquals("saved", replacement.await())
        fixture.trust.allow(fixture.connection, "write")
        assertTrue(runCatching { fixture.prepare().activate { error("credential storage failed") } }.isFailure)
        assertFalse(fixture.trust.allows(fixture.connection, "write"))
        assertTrue(runCatching { fixture.trust.allow(fixture.connection, "write") }.isFailure)
        fixture.prepare().commit()
    }

    @Test fun callerCancellationDoesNotInterruptAnAlreadyStartedDurableRevocation() = runTest {
        val fixture = Fixture()
        fixture.trust.allow(fixture.connection, "write")
        val callerThread = Thread.currentThread().name
        val writing = CompletableDeferred<String>()
        val release = CountDownLatch(1)
        fixture.context.trust.beforeCommit = {
            writing.complete(Thread.currentThread().name)
            check(release.await(10, TimeUnit.SECONDS))
        }
        val prepared = fixture.prepare()
        val committing = launch { prepared.commit() }
        try {
            assertFalse(writing.await() == callerThread)
            committing.cancel()
            assertFalse(fixture.trust.allows(fixture.connection, "write"))
        } finally {
            release.countDown()
        }
        committing.join()
        fixture.context.trust.beforeCommit = {}
        assertTrue(fixture.trust.grants().isEmpty())
        fixture.trust.allow(fixture.connection, "write")
        assertTrue(fixture.trust.allows(fixture.connection, "write"))
    }

    @Test fun aSecondRevocationCannotReleaseDenialDuringSuspendingActivation() = runTest {
        val fixture = Fixture()
        val activated = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val replacement = async {
            fixture.prepare().activate {
                activated.complete(Unit)
                finish.await()
            }
        }
        activated.await()
        val second = fixture.prepare()
        val committing = async { second.commit() }
        runCurrent()
        assertFalse(committing.isCompleted)
        assertFalse(fixture.trust.allows(fixture.connection, "write"))
        assertTrue(runCatching { fixture.trust.allowProvider(fixture.connection) }.isFailure)
        finish.complete(Unit)
        replacement.await()
        committing.await()
        fixture.trust.allow(fixture.connection, "write")
        assertTrue(fixture.trust.allows(fixture.connection, "write"))
    }

    @Test fun olderPreparedActivationRearmsDenialAfterANewerRevocationFinished() = runTest {
        val fixture = Fixture()
        val older = fixture.prepare()
        fixture.prepare().commit()
        fixture.trust.allowProvider(fixture.connection)
        older.activate {
            assertFalse(fixture.trust.allows(fixture.connection, "write"))
            assertTrue(fixture.trust.grants().isEmpty())
            assertTrue(runCatching { fixture.trust.allowProvider(fixture.connection) }.isFailure)
        }
        fixture.trust.allow(fixture.connection, "write")
        assertTrue(fixture.trust.allows(fixture.connection, "write"))
    }

    @Test fun aGrantAlreadyWritingCannotSurviveTheLaterRevocation() = runTest {
        val fixture = Fixture()
        val writing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        fixture.context.trust.beforeCommit = {
            writing.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
        }
        val oldGrant = async(Dispatchers.IO) { fixture.trust.allow(fixture.connection, "write") }
        writing.await()
        try {
            val revocation = fixture.prepare() // does not wait for the grant's disk mutex
            assertFalse(fixture.trust.allows(fixture.connection, "write"))
            val committing = async { revocation.commit() }
            release.countDown()
            oldGrant.await()
            committing.await()
            assertFalse(fixture.trust.allows(fixture.connection, "write"))
            assertTrue(fixture.trust.grants().isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test fun stalePromptCannotGrantOrApproveThePostRevocationConnection() = runTest {
        val fixture = Fixture()
        val dao = mockk<ToolApprovalDao>(relaxed = true)
        val state = MutableStateFlow<ToolApproval?>(null)
        val database = mockk<ChatDatabaseV2> { every { toolApprovalDao() } returns dao }
        val connections = mockk<ToolConnectionRepository> { coEvery { getConnection(fixture.connection.connectionUid) } returns fixture.connection }
        every { dao.observe(any()) } returns state
        every { dao.pending() } returns MutableStateFlow(emptyList())
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns null
        coEvery { dao.insert(any()) } coAnswers {
            state.value = firstArg()
            1L
        }
        coEvery { dao.decide(any(), any()) } coAnswers { state.value = state.value?.copy(state = secondArg()) }
        val approvals = ToolApprovalManager(database, connections, fixture.trust)
        val waiting = async { approvals.authorize(fixture.connection.connectionUid, "run", "call", "write", buildJsonObject {}) }
        runCurrent()
        assertEquals("PENDING", state.value?.state)
        fixture.prepare().activate { /* replacement account/session activation */ }
        assertTrue(runCatching { approvals.alwaysAllow("run:call") }.isFailure)
        assertTrue(runCatching { approvals.alwaysAllowProvider("run:call") }.isFailure)
        assertFalse(fixture.trust.allows(fixture.connection, "write"))
        approvals.decide("run:call", true)
        assertFalse(waiting.await())
        assertEquals("DENIED", state.value?.state)
        coVerify(exactly = 0) { dao.claim(any()) }
    }

    @Test fun olderCompletionCannotReopenANewerDenialAndOldSnapshotsRemainStale() {
        val gate = ToolGrantRevocations()
        val old = requireNotNull(gate.snapshot(listOf("connection")))
        val first = gate.begin("connection")
        val second = gate.begin("connection")
        gate.complete(first)
        assertEquals(null, gate.snapshot(listOf("connection")))
        gate.complete(second)
        assertTrue(gate.snapshot(listOf("connection")) != null)
        assertFalse(gate.current(old))
    }

    private class Fixture {
        val uid = UUID.randomUUID().toString()
        val context = ControlledContext(RuntimeEnvironment.getApplication(), uid)
        val connection = ToolConnection(uid, "Connection", uid, "MCP", "https://example.org/mcp", "OAUTH", null, "client")
        val scope = ScopedToolGrant(7, "schema", "resource", "write")
        val toolId = "$uid:write"
        val trust = ToolTrustStore(context)
        val consent = FreeModelToolConsentStore(context)
        fun prepare() = ToolGrantPersistence.prepareConnectionRevocation(trust, consent, uid)
    }

    private class ControlledContext(base: Context, private val suffix: String) : ContextWrapper(base) {
        val trust = ControlledPreferences(base.getSharedPreferences("trust-$suffix", Context.MODE_PRIVATE))
        private val consent = ControlledPreferences(base.getSharedPreferences("consent-$suffix", Context.MODE_PRIVATE))
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = if (name == "tool_always_allow") trust else consent
    }

    private class ControlledPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        @Volatile var fail = false

        @Volatile var beforeCommit: () -> Unit = {}
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { editor.putBoolean(key, value) }
                override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun clear(): SharedPreferences.Editor = apply { editor.clear() }
                override fun commit(): Boolean {
                    beforeCommit()
                    return !fail && editor.commit()
                }
            }
        }
    }
}
