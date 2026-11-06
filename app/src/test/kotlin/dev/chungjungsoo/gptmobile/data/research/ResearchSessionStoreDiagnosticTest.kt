package dev.chungjungsoo.gptmobile.data.research

import android.app.Application
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResearchSessionStoreDiagnosticTest {
    @Test fun corruptSessionMetadataIsReportedWithoutSuppressingValidSessions() = runBlocking {
        val root = Files.createTempDirectory("research-store-diagnostic").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = root
        }
        try {
            val directory = File(root, "research-history").apply { mkdirs() }
            File(directory, "unreadable.json").writeText("{broken session")
            File(directory, researchHash("valid-run") + ".json").writeText(
                buildJsonObject {
                    put("runId", "valid-run")
                    put("chatId", 7)
                    put("authority", "local")
                    put(
                        "snapshot",
                        buildJsonObject {
                            put("complete", false)
                            put("updatedAt", 1L)
                        }
                    )
                }.toString()
            )
            val rows = mutableListOf<JsonObject>()
            val omissions = mutableListOf<String>()
            ResearchSessionStore(context).streamDiagnosticRecords(7, 16 * 1024, {}, { rows += it }, omissions::add)
            assertEquals(listOf("RESEARCH_SESSION_METADATA_UNAVAILABLE"), omissions)
            assertTrue(rows.any { it.toString().contains("valid-run") })
        } finally {
            root.deleteRecursively()
        }
    }
}
