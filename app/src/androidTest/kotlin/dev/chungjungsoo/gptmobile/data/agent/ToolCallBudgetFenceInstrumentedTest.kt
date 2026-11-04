package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's private filesystem so unsupported file or directory sync fails this device gate. */
@RunWith(AndroidJUnit4::class)
class ToolCallBudgetFenceInstrumentedTest {
    @Test
    fun privateFilesystemFenceSurvivesPayloadLossAndRejectsDeletedChatWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "budget-fence-instrumentation-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val payload = File(directory, "inspectable-budget.json")
        val chatId = 17
        val binding = "accepted-request:${directory.name}"
        fun checkpoint(): ToolCallBudgetCheckpoint {
            val fence = ToolCallBudgetFence.forTurn(directory, chatId, binding)
            return ToolCallBudgetCheckpoint(
                scope = binding,
                read = { if (payload.exists()) Json.parseToJsonElement(payload.readText()) as JsonObject else null },
                write = { value ->
                    fence.mark()
                    FileOutputStream(payload).use { output ->
                        output.write(value.toString().toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    check(Json.parseToJsonElement(payload.readText()) == value)
                },
                hasRecord = { fence.exists() || payload.exists() }
            )
        }
        try {
            val initial = checkpoint()
            assertEquals(0, initial.initialize(limit = 1))
            assertEquals(1, initial.acquire(limit = 1))

            val restored = checkpoint()
            assertEquals(1, restored.initialize(limit = 1))
            assertNull(restored.acquire(limit = 1))
            val staleFence = ToolCallBudgetFence.forTurn(directory, chatId, binding)
            assertTrue(staleFence.exists())

            assertTrue(payload.delete())
            val lostPayload = checkpoint()
            val resetFailure = runCatching { lostPayload.initialize(limit = 1) }.exceptionOrNull()
            assertTrue("A missing inspectable payload must not renew the accepted allowance", resetFailure is IllegalStateException)
            assertFalse(payload.exists())
            assertTrue(ToolCallBudgetFence.forTurn(directory, chatId, binding).exists())

            ToolCallBudgetFence.deleteChat(directory, chatId)
            val staleWrite = runCatching { staleFence.mark() }.exceptionOrNull()
            assertTrue("A stale worker must not recreate a deleted chat's marker", staleWrite is IllegalStateException)
            val recreatedWrite = runCatching { ToolCallBudgetFence.forTurn(directory, chatId, binding).mark() }.exceptionOrNull()
            assertTrue("Recreating a fence object must preserve the chat deletion fence", recreatedWrite is IllegalStateException)
            assertFalse(File(directory, "tool-budget-fences/$chatId").exists())
            assertFalse(payload.exists())
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }
}
