package dev.chungjungsoo.gptmobile.data.privacy

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrivacyBoundaryTest {
    @Test
    fun savedPrivateConversationIsBlockedWithoutAnInheritedContext() = runBlocking {
        assertFalse(PrivacyModeStore.active())
        var loaded = 0
        val failure = runCatching {
            requireUnprotectedConversationPrivacy(41) { id ->
                assertEquals(41, id)
                loaded++
                true
            }
        }.exceptionOrNull()

        assertTrue(failure is UnverifiedServiceTransport)
        assertEquals(1, loaded)
        assertNull(ConversationPrivacyContext.currentOrNull())
    }

    @Test
    fun privateCallerCannotMoveItsTextIntoAnOrdinaryOrUnboundJob() = runBlocking {
        withContext(ConversationPrivacyContext(41, true)) {
            var loaded = false
            for (destination in listOf(42, null)) {
                val failure = runCatching {
                    requireUnprotectedConversationPrivacy(destination) {
                        loaded = true
                        false
                    }
                }.exceptionOrNull()
                assertTrue(failure is UnverifiedServiceTransport)
                assertTrue(PrivacyModeStore.active())
                assertEquals(41, ConversationPrivacyContext.currentOrNull()?.chatId)
            }
            assertFalse(loaded)
        }
        assertNull(ConversationPrivacyContext.currentOrNull())
    }

    @Test
    fun missingDeletedAndUnreadableConversationsNeverAuthorizeExternalWork() = runBlocking {
        assertTrue(runCatching { requireUnprotectedConversationPrivacy(41) { null } }.isFailure)
        assertTrue(runCatching { requireUnprotectedConversationPrivacy(0) { false } }.isFailure)
        val unavailable = IOException("Synthetic database failure")
        assertSame(unavailable, runCatching { requireUnprotectedConversationPrivacy(41) { throw unavailable } }.exceptionOrNull())
    }

    @Test
    fun ordinaryConversationGetsItsOwnContextAndRestoresTheCallingThread() = runBlocking {
        val context = requireUnprotectedConversationPrivacy(42) { false }
        withContext(Dispatchers.Default + context) {
            assertEquals(42, ConversationPrivacyContext.currentOrNull()?.chatId)
            assertFalse(PrivacyModeStore.active())
            PrivacyModeStore.requireUnprotectedTransport()
        }
        assertNull(ConversationPrivacyContext.currentOrNull())
    }

    @Test
    fun standaloneResearchMayRunOnlyWithoutAnInheritedPrivateSource() = runBlocking {
        val context = requireUnprotectedConversationPrivacy(null) { error("An unbound job has no saved chat") }
        assertNull(context.chatId)
        assertFalse(context.enabled)
    }

    @Test
    fun appOwnedQueuedWorkReloadsPrivacyAfterAdmissionWithoutAffectingAnotherChat() = runBlocking {
        val savedLock = AtomicBoolean(false)
        val admitted = requireUnprotectedConversationPrivacy(41) { savedLock.get() }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queued = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var privateDispatches = 0
        try {
            val pending = owner.async(admitted) {
                queued.complete(Unit)
                release.await()
                runCatching {
                    withContext(requireUnprotectedConversationPrivacy(41) { savedLock.get() }) {
                        privateDispatches++
                    }
                }.exceptionOrNull()
            }
            withTimeout(5000) { queued.await() }
            savedLock.set(true)
            val ordinary = owner.async(requireUnprotectedConversationPrivacy(42) { false }) {
                assertFalse(PrivacyModeStore.active())
                assertEquals(42, ConversationPrivacyContext.currentOrNull()?.chatId)
                "ordinary dispatched"
            }
            release.complete(Unit)

            assertTrue(withTimeout(5000) { pending.await() } is UnverifiedServiceTransport)
            assertEquals("ordinary dispatched", withTimeout(5000) { ordinary.await() })
            assertEquals(0, privateDispatches)
        } finally {
            owner.cancel()
        }
    }
}
