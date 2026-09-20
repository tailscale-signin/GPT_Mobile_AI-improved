package dev.chungjungsoo.gptmobile.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KnowledgeGraphEngineTest {

    private lateinit var engine: KnowledgeGraphEngine

    @Before
    fun setUp() {
        engine = KnowledgeGraphEngine()
    }

    @Test
    fun `test add entities and relations and query subgraph`() {
        val user = KnowledgeEntity(id = "user_1", name = "Alice", type = "PERSON")
        val kotlin = KnowledgeEntity(id = "kotlin", name = "Kotlin", type = "TECHNOLOGY")
        val android = KnowledgeEntity(id = "android", name = "Android", type = "PROJECT")

        engine.addEntity(user)
        engine.addEntity(kotlin)
        engine.addEntity(android)

        engine.addRelation(KnowledgeRelation(sourceId = "user_1", relationType = "USES", targetId = "kotlin"))
        engine.addRelation(KnowledgeRelation(sourceId = "kotlin", relationType = "USES", targetId = "android"))

        val userEntity = engine.getEntity("user_1")
        assertNotNull(userEntity)
        assertEquals("Alice", userEntity?.name)

        val subgraph = engine.querySubgraph(seedEntityId = "user_1", maxDepth = 2)
        assertEquals(2, subgraph.size)
        assertEquals("user_1", subgraph[0].relation.sourceId)
        assertEquals("kotlin", subgraph[0].relation.targetId)
        assertEquals("kotlin", subgraph[1].relation.sourceId)
        assertEquals("android", subgraph[1].relation.targetId)
    }

    @Test
    fun `test natural language pattern extraction`() {
        val text = """
            Alice prefers Kotlin
            Bob uses Android
            Charlie lives in Seattle
        """.trimIndent()

        engine.extractAndStoreFromText(text)

        val alice = engine.getEntity("alice")
        assertNotNull(alice)
        assertEquals("Alice", alice?.name)

        val facts = engine.queryContextualFacts("What does Alice like?")
        assertTrue(facts.any { it.relation.relationType == "PREFERS" && it.target.id == "kotlin" })
    }

    @Test
    fun `test clear knowledge graph`() {
        engine.addEntity(KnowledgeEntity(id = "e1", name = "Test", type = "CONCEPT"))
        assertEquals(1, engine.getAllEntities().size)

        engine.clear()
        assertEquals(0, engine.getAllEntities().size)
    }
}
