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

    @Test fun sourceSearchMatchesThePathWithoutInventingEdgesToFilteredNodes() {
        val graph = repositoryGraphPresentation(json("""{"nodes":[{"id":"a","qualified":"Store.save","path":"app/data/Store.kt"},{"id":"b","qualified":"Store.read","path":"app/data/Store.kt"},{"id":"c","qualified":"Screen.render","path":"app/ui/Screen.kt"}],"edges":[{"source":"a","target":"b","relation":"calls"},{"source":"c","target":"a","relation":"calls"}]}"""))
        val result = graph.search("  DATA/store.KT ").page(0)
        assertEquals(listOf("a", "b"), result.nodes.map { it.graphText("id") })
        assertEquals(1, result.edges.size)
        assertEquals("a", result.edges.single().graphText("source"))
        assertEquals(listOf("c"), graph.search("Screen.render").nodes.map { it.graphText("id") })
        assertTrue(graph.search("Missing.kt").page(0).edges.isEmpty())
    }

    @Test fun selectedSourceDetailsRetainBothDirectionsAndExcludeUnrelatedLinks() {
        val graph = repositoryGraphPresentation(json("""{"nodes":[{"id":"a"},{"id":"b"},{"id":"c"}],"edges":[{"source":"a","target":"b","relation":"imports"},{"source":"c","target":"a","relation":"calls"},{"source":"b","target":"c","relation":"references"}]}"""))
        assertEquals(listOf("imports", "calls"), graph.relationships("a").map { it.graphText("relation") })
        assertTrue(graph.relationships("unknown").isEmpty())
    }

    @Test fun neighborhoodKeepsRealNeighborsTogetherAndReportsOffDiagramConnections() {
        val graph = repositoryGraphPresentation(json("""{"nodes":[{"id":"center"},{"id":"unrelated"},{"id":"caller"},{"id":"callee"}],"edges":[{"source":"caller","target":"center","relation":"calls"},{"source":"center","target":"callee","relation":"calls"}]}"""))
        val small = graph.neighborhood("center", 2)
        assertEquals(listOf("center", "caller"), small.nodes.map { it.graphText("id") })
        assertEquals(1, small.edges.size)
        assertEquals(1, small.externalConnections)
        assertEquals(2, small.omittedNodes)
        val neighborhood = graph.neighborhood("center", 3)
        assertEquals(setOf("center", "caller", "callee"), neighborhood.nodes.map { it.graphText("id") }.toSet())
        assertEquals(2, neighborhood.edges.size)
        assertEquals(0, neighborhood.externalConnections)
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
