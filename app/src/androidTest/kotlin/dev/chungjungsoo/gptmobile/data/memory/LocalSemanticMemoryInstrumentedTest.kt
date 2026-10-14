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
            val document = dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeDocument("reference", "Reference", "hash", chatId = 77)
            val chunk = dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeChunk("reference:0", document.id, 0, 0, 33, "Astronomy reference about galaxies")
            engine.indexDocument(document, listOf(chunk))
            engine.releaseWhenIdle(0)
            val persisted = MyObjectBox.builder().androidContext(context).directory(File(directory, "memory-vectors-use-qa-v1")).build()
            try {
                assertTrue("Protected fact vectors were persisted in the document index", persisted.boxFor(MemoryVector::class.java).all.all { it.factId.startsWith("doc:") })
            } finally {
                persisted.close()
            }
            engine = LocalSemanticMemory(context)
            assertTrue("A fresh derived cache exposed facts before canonical hydration", engine.search("bread", "project:private").isEmpty())
            assertTrue("Reopened native document index lost its chunk", engine.searchDocuments("galaxies", 77, null).containsKey(chunk.id))
            assertTrue("Native document search crossed chat scope", engine.searchDocuments("galaxies", 78, null).isEmpty())
            // The encrypted repository owns durability; callers hydrate this disposable fact cache.
            // MemoryDurabilityInstrumentedTest separately covers canonical storage and restore.
            engine.synchronize(facts)
            assertTrue("Canonical snapshot failed to rebuild project fact search", engine.search("bread", "project:private").containsKey("project"))
            engine.synchronize(facts.map { if (it.id == "project") it.copy(scope = "project:moved") else it })
            assertFalse("Scope change left a fact visible to its former project", engine.search("bread", "project:private").containsKey("project"))
            assertTrue("Scope change lost the fact in its authorized project", engine.search("bread", "project:moved").containsKey("project"))
            engine.synchronize(facts.take(1))
            assertTrue("Native index deletion failed: ${engine.status.value.detail}", engine.status.value.available)
            val afterDeletion = engine.search("bread", "project:moved")
            assertTrue("Native search failed after deletion: ${engine.status.value.detail}", engine.status.value.available)
            assertFalse("Deleted project fact remained in native search", afterDeletion.containsKey("project"))
            engine.clearFacts()
            assertTrue("Fact-only clear lost document vectors", engine.searchDocuments("galaxies", 77, null).containsKey(chunk.id))
            assertTrue("Fact-only clear retained personal facts", engine.search("astronomy", "personal").isEmpty())
            engine.clear()
            val afterClear = engine.search("astronomy", "personal")
            assertTrue("Native search failed after clearing: ${engine.status.value.detail}", engine.status.value.available)
            assertTrue("Cleared native index still returned facts", afterClear.isEmpty())
            assertTrue("Cleared native index still returned document chunks", engine.searchDocuments("galaxies", 77, null).isEmpty())
            // Unlike an explicit lifecycle close/reopen, genuine system memory pressure
            // must defer native allocation until the bounded cooldown has expired.
            engine.releaseWhenIdle(0, memoryPressure = true)
            assertFalse("Memory-pressure release did not pause semantic recall", engine.status.value.available)
            engine.synchronize(facts)
            assertFalse("Pressure cooldown was bypassed by eager synchronization", engine.status.value.available)
        } finally {
            engine.releaseWhenIdle(0)
            directory.deleteRecursively()
        }
    }
}
