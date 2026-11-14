package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSelfTestPrerequisitesTest {
    private val head = "a".repeat(40)
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val source = json("""{"trusted":true,"success":true,"binding":"task","repository":"owner/repo","tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$head"}}""")
    private val graph = json("""{"trusted":true,"success":true,"binding":"task","repository":"owner/repo","tool":"repository_graph","arguments":{"action":"status"},"result":{"commit_sha":"$head","status":"NOT_INDEXED"}}""")

    @Test fun graphObservationRequiresTheSelectedTaskRepositoryAndRevision() {
        assertEquals("NOT_INDEXED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, graph)))
        for (change in listOf("binding" to "other-task", "repository" to "owner/another", "repository_role" to "READ_SOURCE")) {
            assertEquals("UNOBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, JsonObject(graph + (change.first to JsonPrimitive(change.second))))))
        }
        assertEquals("REVISION_MISMATCH", GitHubSelfTestPrerequisites.graphObservation(listOf(source, json(graph.toString().replace(head, "b".repeat(40))))))
        assertEquals("UNAVAILABLE", GitHubSelfTestPrerequisites.graphObservation(listOf(source, JsonObject(graph + ("success" to JsonPrimitive(false))))))
    }

    @Test fun missingAndInvalidSelectedHeadsNeverBorrowAStandaloneGraphReceipt() {
        assertEquals("UNOBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(graph)))
        assertNull(GitHubSelfTestPrerequisites.selected(listOf(json(source.toString().replace(head, "main")), graph)))
        assertEquals("UNOBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(JsonObject(source + ("trusted" to JsonPrimitive(false))), graph)))
    }

    @Test fun latestGraphPreparationReplacesMissingIndexWithoutInventingCompletedCoverage() {
        val partial = json("""{"trusted":true,"success":true,"binding":"task","repository":"owner/repo","tool":"repository_graph","arguments":{"action":"index"},"result":{"has_more":true,"index_cursor":"next","graph":{"repository":"owner/repo","commit_sha":"$head"}}}""")
        val indexed = json(partial.toString().replace("\"has_more\":true", "\"has_more\":false"))
        assertEquals("OBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, graph, partial)))
        assertEquals("OBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, graph, partial, indexed)))
        assertEquals("UNAVAILABLE", GitHubSelfTestPrerequisites.graphObservation(listOf(source, graph, JsonObject(partial + ("success" to JsonPrimitive(false))))))
        assertEquals("REVISION_MISMATCH", GitHubSelfTestPrerequisites.graphObservation(listOf(source, graph, json(partial.toString().replace(head, "b".repeat(40))))))
        val metadataMismatch = JsonObject(partial + ("result" to json("""{"has_more":false,"graph":{"repository":"other/repo","commit_sha":"$head"}}""")))
        assertEquals("UNOBSERVED", GitHubSelfTestPrerequisites.graphObservation(listOf(source, metadataMismatch)))
    }

    @Test fun aMissingJobDependencyDoesNotPretendAWorkflowRanOrOverrideItsFailure() {
        val tools = listOf(AgentToolDefinition("github_ci", "CI reads", json("""{"properties":{"action":{"enum":["jobs","job","logs"]}}}""")))
        assertEquals("UNOBSERVED", GitHubSelfTestWorkflowJobs.dependencyObservation(tools, emptyList()))
        assertEquals("MISSING_EXECUTED_JOB", GitHubSelfTestWorkflowJobs.dependencyObservation(tools, listOf(source)))
        val failure = json("""{"trusted":true,"success":false,"binding":"task","repository":"owner/repo","tool":"github_ci","arguments":{"action":"jobs","run_id":7},"error_code":"PERMISSION_DENIED"}""")
        assertEquals("READ_FAILED", GitHubSelfTestWorkflowJobs.dependencyObservation(tools, listOf(source, failure)))
        assertNull(GitHubSelfTestWorkflowJobs.dependencyObservation(emptyList(), listOf(source)))
    }
}
