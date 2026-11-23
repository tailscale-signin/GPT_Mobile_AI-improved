package dev.chungjungsoo.gptmobile.data.privacy

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrivacyContextTest {
    @Test
    fun concurrentPrivateAndOrdinaryConversationsKeepSeparatePolicyOnTheSameThread() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val privateReady = CompletableDeferred<Unit>()
            val ordinaryReady = CompletableDeferred<Unit>()
            val private = async(dispatcher + ConversationPrivacyContext(11, true)) {
                assertTrue(PrivacyModeStore.active())
                privateReady.complete(Unit)
                ordinaryReady.await()
                repeat(20) {
                    yield()
                    assertTrue(PrivacyModeStore.active())
                    assertEquals(11, ConversationPrivacyContext.capture()?.chatId)
                }
                assertTrue(runCatching { PrivacyModeStore.requireUnprotectedTransport() }.exceptionOrNull() is UnverifiedServiceTransport)
            }
            val ordinary = async(dispatcher + ConversationPrivacyContext(12, false)) {
                privateReady.await()
                ordinaryReady.complete(Unit)
                repeat(20) {
                    yield()
                    assertFalse(PrivacyModeStore.active())
                    assertEquals(12, ConversationPrivacyContext.capture()?.chatId)
                    PrivacyModeStore.requireUnprotectedTransport()
                }
            }
            private.await()
            ordinary.await()
            withContext(dispatcher) { assertNull(ConversationPrivacyContext.capture()) }
        }
    }

    @Test
    fun queuedFlowRestoresItsConversationWhenCollectedByAnotherChat() = runBlocking {
        val private = ConversationPrivacyContext(31, true)
        val request = flow {
            yield()
            withContext(Dispatchers.IO) {
                assertSame(private, ConversationPrivacyContext.capture())
                assertTrue(PrivacyModeStore.active())
            }
            emit(ConversationPrivacyContext.capture()?.chatId)
        }.flowOn(private)
        withContext(ConversationPrivacyContext(32, false)) {
            assertEquals(listOf(31), request.toList())
            assertFalse(PrivacyModeStore.active())
            assertEquals(32, ConversationPrivacyContext.capture()?.chatId)
        }
    }

    @Test
    fun executorCallbackUsesCapturedPolicyAndAlwaysRestoresThePreviousThread() = runBlocking {
        val captured = withContext(ConversationPrivacyContext(41, true)) { ConversationPrivacyContext.capture() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                val ordinary = ConversationPrivacyContext(42, false)
                ConversationPrivacyContext.withCaptured(ordinary) {
                    val failure = runCatching {
                        ConversationPrivacyContext.withCaptured(captured) { PrivacyModeStore.requireUnprotectedTransport() }
                    }.exceptionOrNull()
                    assertTrue(failure is UnverifiedServiceTransport)
                    assertSame(ordinary, ConversationPrivacyContext.capture())
                    PrivacyModeStore.requireUnprotectedTransport()
                }
                assertNull(ConversationPrivacyContext.capture())
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Unit
    }
}
