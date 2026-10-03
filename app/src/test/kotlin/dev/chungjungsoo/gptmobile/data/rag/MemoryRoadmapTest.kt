package dev.chungjungsoo.gptmobile.data.rag

import dev.chungjungsoo.gptmobile.data.knowledge.DocumentSearchIndex
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeChunk
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeDocument
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope
import dev.chungjungsoo.gptmobile.data.memory.MemoryEnrichmentWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRoadmapTest {
    @Test fun `project memory requires explicit personal opt in and never accepts another project`() {
        val project = KnowledgeProject("alpha", "Alpha")
        val scope = MemoryScope("project:alpha", false, project)
        assertTrue(scope.accepts("project:alpha"))
        assertFalse(scope.accepts("personal"))
        assertFalse(scope.accepts("project:beta"))
        assertTrue(scope.copy(includePersonal = true).accepts("personal"))
        assertFalse(scope.copy(isTemporary = true).accepts("project:alpha"))
        assertTrue(scope.accepts(KnowledgeDocument("d", "Shared", "hash", projectId = "alpha"), 2))
        assertFalse(scope.accepts(KnowledgeDocument("d", "Foreign", "hash", projectId = "beta"), 2))
        assertFalse(scope.accepts(KnowledgeDocument("d", "Other chat", "hash", chatId = 3), 2))
        assertFalse(scope.accepts(KnowledgeDocument("d", "Deleted", "hash", chatId = 2, deleted = true), 2))
    }

    @Test fun `FTS expressions escape operators and retain non Latin text`() {
        assertEquals("\"kotlin\"* OR \"drop\"* OR \"table\"*", DocumentSearchIndex.expression("Kotlin\"; DROP TABLE --"))
        assertEquals("\"東京\"* OR \"記憶\"*", DocumentSearchIndex.expression("東京 記憶"))
        assertNull(DocumentSearchIndex.expression("* ()"))
    }

    @Test fun `broad document context spreads evidence across files`() {
        val chunks = (0..10).map { KnowledgeChunk("a:$it", "a", it, 0, 20, "a") } +
            (0..10).map { KnowledgeChunk("b:$it", "b", it, 0, 20, "b") }
        assertEquals(listOf("a:0", "b:0", "a:1", "b:1", "a:2"), MemoryDocumentRepository.diversify(chunks, 5).map { it.id })
    }

    @Test fun `background extraction accepts structured output and rejects incomplete output`() {
        assertNotNull(MemoryEnrichmentWorker.parse("```json\n{\"observations\":[]}\n```"))
        assertNull(MemoryEnrichmentWorker.parse("{\"observations\":"))
        assertNull(MemoryEnrichmentWorker.parse("I think you like Kotlin"))
    }

    @Test fun `recall stays precise at four and sixteen thousand facts`() {
        for (size in listOf(4096, 16384)) {
            val facts = (0 until size).map { VaultFact("$it", MemoryLearning.observation("Reference code QZX$it")) }
            val matches = MemoryRecallPolicy.rank("QZX${size - 1}", facts)
            assertEquals(listOf("${size - 1}"), matches.map { it.id })
        }
    }
}
