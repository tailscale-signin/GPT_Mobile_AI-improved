package dev.chungjungsoo.gptmobile.data.agent

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecoveryEvidenceCorpusTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sessionStoreRetainsTheReferenceAcrossRestartAndDeletionRemovesEveryPiece() = runTest {
        val files = temporary.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getFilesDir(): File = files
        }
        val corpus = RecoveryEvidenceCorpus.Builder().apply { add("source", "source:S1", "Retained original evidence") }.build()
        val store = ResearchSessionStore(context)
        val (reference, restored) = store.retainRecoveryCorpus(1, "1:1", "authority", corpus)
        assertEquals(corpus.revision, restored.revision)
        assertEquals(reference, ResearchSessionStore(context).retainRecoveryCorpus(1, "1:1", "authority", corpus).first)
        store.deleteChat(1)
        assertTrue(File(files, "research-history/recovery-corpora").listFiles().orEmpty().isEmpty())
        assertTrue(runCatching { store.retainRecoveryCorpus(1, "1:1", "authority", corpus) }.isFailure)
    }

    @Test fun retryReferencesReuseImmutablePiecesAndRestoreOnlyUnderTheirOriginalAuthority() {
        val directory = temporary.newFolder()
        val store = RecoveryEvidenceCorpusStore(directory)
        val corpus = RecoveryEvidenceCorpus.Builder().apply {
            add("source", "source:S1", "🧪 Evidence passage ".repeat(5000))
            add("source", "source:S2", "🧪 Evidence passage ".repeat(5000))
        }.build()
        assertEquals(1, corpus.records.size)
        val reference = store.retain(1, "1:1", "scope", corpus)
        val files = directory.listFiles().orEmpty().map { it.name }.toSet()
        repeat(4) { assertEquals(reference, store.retain(1, "1:1", "scope", corpus)) }
        assertEquals(files, directory.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(corpus.content, RecoveryEvidenceCorpusStore(directory).read(reference, 1, "1:1", "scope").content)
        assertTrue(runCatching { store.read(reference, 1, "1:1", "other") }.isFailure)
        val other = store.retain(2, "2:1", "scope", corpus)
        store.deleteChat(1)
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("1-") })
        assertEquals(corpus.revision, store.read(other, 2, "2:1", "scope").revision)
        val piece = directory.listFiles().orEmpty().first { it.extension == "recovery-piece" }
        piece.writeText("changed")
        assertTrue(runCatching { store.read(other, 2, "2:1", "scope") }.isFailure)
    }

    @Test fun sourceIdsProvideFocusedReadsAndPreviousRecoveryPagesCannotNest() = runTest {
        val run = AgentRun("old", 1, 1, 2, "profile", "FREE", "model", status = "FAILED")
        val message = MessageV2(id = 2, chatId = 1, content = "A retained partial answer", platformType = "profile", currentRunId = "old")
        val read = ToolEvent("page", "old", 0, "old-page", null, "Saved work", "read_recovery_context", "read_recovery_context", "{}", "PREVIOUS_RECURSIVE_BUNDLE", "TEXT", "COMPLETED")
        val source = ResearchSource("S1", "https://example.org/source", "Study", passage = "Unique saved source passage 🧪")
        val context = ResponseRecoveryContext(listOf(run, run), listOf(message, message), listOf(read, read), listOf(ResearchSnapshot("task", sources = listOf(source))))
        assertFalse(context.content.contains("PREVIOUS_RECURSIVE_BUNDLE"))
        assertEquals(1, context.corpus.records.count { it.kind == "answer" })
        val result = context.tool().execute(
            "focused",
            buildJsonObject {
                put("evidence_id", "source:S1")
                put("corpus_revision", context.corpus.revision)
            }
        )
        val page = (result.content as ToolResultContent.Json).value.jsonObject
        assertTrue(page.getValue("saved_work").jsonPrimitive.content.contains("Unique saved source passage"))
        assertFalse(page.getValue("saved_work").jsonPrimitive.content.contains("retained partial answer"))
        assertTrue(context.tool().execute("stale", buildJsonObject { put("corpus_revision", "old") }).isError)
        assertTrue(context.tool().execute("unknown", buildJsonObject { put("evidence_id", "source:missing") }).isError)
    }
}
