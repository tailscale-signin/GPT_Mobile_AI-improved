package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test fun boundedPathSearchStaysDistinctFromAnEmptyCompletedSearch() {
        val empty = RepositoryGraphPresentation(emptyList(), emptyList())
        val limited = requireNotNull(empty.resultGuidance("PATH_SEARCH_LIMIT_REACHED"))
        assertTrue(limited.contains("reached its limit"))
        assertTrue(limited.contains("Narrow the source or target"))
        assertFalse(limited.contains("No matching relationship"))
        val noPath = requireNotNull(empty.resultGuidance("NO_PATH_IN_INDEX"))
        assertTrue(noPath.contains("No matching relationship in this index"))
        assertEquals(noPath, empty.resultGuidance("NOT_FOUND_IN_INDEX"))
        assertNull(empty.resultGuidance("INDEXED"))
        assertNull(empty.resultGuidance("UNREPORTED_STATUS"))
        val visible = repositoryGraphPresentation(json("""{"nodes":[{"id":"observed-source"}]}"""))
        for (status in listOf("PATH_SEARCH_LIMIT_REACHED", "NO_PATH_IN_INDEX", "NOT_FOUND_IN_INDEX")) {
            assertNull(visible.resultGuidance(status))
        }
    }

    @Test fun incompleteImpactKeepsItsPartialCandidatesAndShowsTheSearchLimit() {
        val result = json("""{"status":"IMPACT_SEARCH_LIMIT_REACHED","nodes":[{"id":"source"},{"id":"candidate"}],"edges":[{"source":"candidate","target":"source","relation":"call_candidate"}],"impact_search_complete":false}""")
        val graph = repositoryGraphPresentation(result)
        val guidance = requireNotNull(graph.resultGuidance(result.graphText("status")))
        assertTrue(guidance.contains("impact search reached its limit"))
        assertTrue(guidance.contains("Results may be incomplete"))
        assertFalse(guidance.contains("No matching relationship"))
        assertEquals(listOf("source", "candidate"), graph.nodes.map { it.graphText("id") })
        assertTrue(graph.edges.single().graphCandidate())
        assertEquals(guidance, RepositoryGraphPresentation(emptyList(), emptyList()).resultGuidance("IMPACT_SEARCH_LIMIT_REACHED"))
    }

    @Test fun candidateLinksRetainTheirUncertaintyThroughFilteringAndPagination() {
        val graph = repositoryGraphPresentation(json("""{"nodes":[{"id":"folder","path":"src"},{"id":"a","path":"src/A.kt"},{"id":"b","path":"src/B.kt"}],"edges":[{"source":"folder","target":"a","relation":"contains","confidence":"verified"},{"source":"a","target":"b","relation":"call_candidate","confidence":"candidate"}]}"""))
        assertFalse(graph.edges.first().graphCandidate())
        assertTrue(graph.edges.last().graphCandidate())
        val selected = graph.search(".kt").page(0)
        assertEquals(1, selected.edges.size)
        assertTrue(selected.edges.single().graphCandidate())
        assertEquals("candidate", selected.edges.single().graphText("confidence"))
    }

    @Test fun phonePagesRetainLinksThatExtendBeyondTheReturnedSourceWindow() {
        val graph = repositoryGraphPresentation(json("""{"nodes":[{"id":"a"},{"id":"b"}],"edges":[{"source":"a","target":"b","relation":"contains"}],"external_connections":3}"""))
        assertEquals(3, graph.externalConnections)
        assertEquals(3, graph.neighborhood("a").externalConnections)
        assertEquals(4, graph.neighborhood("a", 1).externalConnections)
    }

    @Test fun folderSourceLinksUseThePinnedTreeWithoutLineAnchors() {
        val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "profile")
        val folder = json("""{"kind":"directory","path":"src/feature one","line":12}""")
        assertEquals("https://github.com/owner/repo/tree/${context.commitSha}/src/feature%20one", repositoryGraphSourceUrl(context, folder))
    }

    @Test fun symbolSourceLinksEncodeThePathAndRetainObservedLineRanges() {
        val context = GitHubConversationContext("github", "owner", "repo", "main", "b".repeat(40), "profile")
        val symbol = json("""{"kind":"function","path":"src/My File#.kt","line":12,"end_line":15,"url":"https://example.com/other","commit_sha":"old"}""")
        assertEquals("https://github.com/owner/repo/blob/${context.commitSha}/src/My%20File%23.kt#L12-L15", repositoryGraphSourceUrl(context, symbol))
        assertEquals("https://github.com/owner/repo/blob/${context.commitSha}/src/A.kt#L4", repositoryGraphSourceUrl(context, json("""{"path":"src/A.kt","start_line":4,"end_line":3}""")))
    }

    @Test fun sourceLinksRejectTraversalAtPinnedRevision() {
        val context = GitHubConversationContext("github", "owner", "repo", "main", "c".repeat(40), "profile")
        for (path in listOf("../outside.kt", "src/./A.kt", "/absolute.kt", "src//A.kt", "src\\A.kt", "src\nA.kt")) {
            assertNull(repositoryGraphSourceUrl(context, kotlinx.serialization.json.buildJsonObject { put("path", kotlinx.serialization.json.JsonPrimitive(path)) }))
        }
    }

    @Test fun unresolvedRevisionsCannotBecomeSourceLinkContexts() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubConversationContext("github", "owner", "repo", "main", "main", "profile")
        }
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
