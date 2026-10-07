package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeEntity
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeFact
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeRelation
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged tokenizer/encoder and ObjectBox JNI, not a fake vector store. */
@RunWith(AndroidJUnit4::class)
class LocalSemanticMemoryInstrumentedTest {
    @Test
    fun nativeIndexSurvivesReopenAndHonorsScopeAndDeletion() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "semantic-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = directory
        }
        fun fact(id: String, text: String, scope: String) = VaultFact(
            id,
            KnowledgeFact(KnowledgeEntity("user", "User", "PERSON"), KnowledgeRelation("user", "PREFERS", id), KnowledgeEntity(id, text, "PREFERENCE")),
            scope = scope
        )
        var engine = LocalSemanticMemory(context)
        try {
            val facts = listOf(fact("personal", "astronomy and observing stars", "personal"), fact("project", "baking sourdough bread", "project:private"))
            engine.synchronize(facts)
            assertTrue(engine.status.value.detail, engine.status.value.available)
            assertEquals(2, engine.status.value.indexed)
            val first = engine.search("observing the night sky", "personal")
            assertTrue("First native search failed: ${engine.status.value.detail}", engine.status.value.available)
            assertTrue("Native search lost the indexed personal fact", first.containsKey("personal"))
            assertFalse("Personal scope leaked a private project fact", first.containsKey("project"))
            assertTrue("Native search returned a non-finite score", first.values.all { it.isFinite() })
            // Force callers to migrate across dispatchers while native readers are repeatedly closed.
            (1..12).map { index ->
                async(if (index % 2 == 0) Dispatchers.IO else Dispatchers.Default) {
                    val result = engine.search("stars", "personal")
                    assertTrue("Native search failed during close/reopen cycle $index", result.containsKey("personal"))
                    engine.releaseWhenIdle(0)
                    engine.synchronize(facts)
                }
            }.awaitAll()
            assertTrue("Engine became unavailable after close/reopen cycles: ${engine.status.value.detail}", engine.status.value.available)
            engine.releaseWhenIdle(0)
            engine = LocalSemanticMemory(context)
            assertTrue("Reopened native index lost its project fact", engine.search("bread", "project:private").containsKey("project"))
            engine.synchronize(facts.take(1))
            assertTrue("Native index deletion failed: ${engine.status.value.detail}", engine.status.value.available)
            val afterDeletion = engine.search("bread", "project:private")
            assertTrue("Native search failed after deletion: ${engine.status.value.detail}", engine.status.value.available)
            assertFalse("Deleted project fact remained in native search", afterDeletion.containsKey("project"))
            engine.clear()
            val afterClear = engine.search("astronomy", "personal")
            assertTrue("Native search failed after clearing: ${engine.status.value.detail}", engine.status.value.available)
            assertTrue("Cleared native index still returned facts", afterClear.isEmpty())
        } finally {
            engine.releaseWhenIdle(0)
            directory.deleteRecursively()
        }
    }
}
