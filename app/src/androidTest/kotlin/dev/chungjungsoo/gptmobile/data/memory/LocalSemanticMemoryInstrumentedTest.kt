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
            assertTrue(first.containsKey("personal"))
            assertFalse(first.containsKey("project"))
            assertTrue(first.values.all { it.isFinite() })
            engine.releaseWhenIdle(0)
            engine = LocalSemanticMemory(context)
            assertTrue(engine.search("bread", "project:private").containsKey("project"))
            engine.synchronize(facts.take(1))
            assertFalse(engine.search("bread", "project:private").containsKey("project"))
            engine.clear()
            assertTrue(engine.search("astronomy", "personal").isEmpty())
        } finally {
            engine.releaseWhenIdle(0)
            directory.deleteRecursively()
        }
    }
}
