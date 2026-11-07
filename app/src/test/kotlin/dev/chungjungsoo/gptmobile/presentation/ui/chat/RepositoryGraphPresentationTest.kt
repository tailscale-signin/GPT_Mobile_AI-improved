package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryGraphPresentationTest {
    @Test fun architectureFilesAndDependenciesBecomeTheActualBubblesAndArrows() {
        val graph = repositoryGraphPresentation(json("""{"communities":[{"id":"group-1","name":"app","files":["app/Main.kt","app/Store.kt","test/StoreTest.kt"]}],"dependencies":{"app/Main.kt":["app/Store.kt","app/Store.kt"]},"hubs":[{"path":"app/Store.kt","connections":1}]}"""))
        assertEquals(setOf("app/Main.kt", "app/Store.kt", "test/StoreTest.kt"), graph.nodes.map { it.graphText("id") }.toSet())
        assertEquals("Main.kt", graph.nodes.first { it.graphText("id") == "app/Main.kt" }.graphText("name"))
        assertEquals(1, graph.edges.size)
        assertEquals("app/Main.kt", graph.edges.single().graphText("source"))
        assertEquals("app/Store.kt", graph.edges.single().graphText("target"))
        assertTrue(graph.nodes.none { it.graphText("id") == "group-1" })
    }

    @Test fun symbolResultsRejectDuplicateOrMissingNodesAndUnresolvedEdges() {
        val graph = repositoryGraphPresentation(json("""{"nodes":[{"id":"a","name":"A"},{"id":"a","name":"duplicate"},{"name":"missing"},{"id":"b","name":"B"}],"edges":[{"source":"a","target":"b","relation":"calls"},{"source":"a","target":"b","relation":"calls"},{"source":"a","target":"unknown"}]}"""))
        assertEquals(listOf("a", "b"), graph.nodes.map { it.graphText("id") })
        assertEquals(1, graph.edges.size)
    }

    @Test fun pagesKeepEverySourceReachableWithoutDrawingEdgesToInvisibleNodes() {
        val nodes = (1..25).joinToString(",") { "{\"id\":\"$it\",\"name\":\"File$it.kt\"}" }
        val graph = repositoryGraphPresentation(json("""{"nodes":[$nodes],"edges":[{"source":"1","target":"2"},{"source":"1","target":"25"},{"source":"13","target":"14"}]}"""))
        assertEquals(listOf(12, 12, 1), (0..2).map { graph.page(it).nodes.size })
        assertEquals(graph.nodes, (0..2).flatMap { graph.page(it).nodes })
        assertEquals(listOf(1, 1, 0), (0..2).map { graph.page(it).edges.size })
        assertTrue(graph.page(3).nodes.isEmpty())
    }

    @Test fun unavailableArchitectureDoesNotInventRepositoryNodesOrRelationships() {
        val graph = repositoryGraphPresentation(json("""{"status":"NOT_INDEXED","communities":[{"id":"group","name":"Unknown"}]}"""))
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
