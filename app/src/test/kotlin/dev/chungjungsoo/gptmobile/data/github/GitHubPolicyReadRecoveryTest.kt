package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPolicyReadRecoveryTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private val issue = AgentToolDefinition(
        "github_issue__github_api",
        "Read issue comments",
        json("""{"type":"object","properties":{"action":{"enum":["comments"]},"issue_number":{"type":"integer"},"branch":{"type":"string"}},"required":["action","issue_number"],"additionalProperties":false}""")
    )
    private val originalCall = ProviderEvent.ToolCall("original-comments", issue.name, json("""{"action":"comments","issue_number":674,"branch":"main"}"""))

    private class Journal {
        val entries = linkedMapOf<String, JsonObject>()
        fun book(revision: String?, tools: List<AgentToolDefinition>) = GitHubEvidenceBook(
            binding = "same-account-task",
            repository = "owner/repo",
            branch = "main",
            trustedTools = tools.mapTo(hashSetOf()) { it.name },
            load = { entries[it] },
            save = { key, value -> entries[key] = value },
            retainedIds = { entries.keys.filter { it.startsWith("ev_") } },
            currentToolContracts = tools.associate { it.name to it.inputSchema },
            authorizationRevision = revision
        )
    }

    private fun rejected(call: ProviderEvent.ToolCall = originalCall) = requireNotNull(
        ToolFailureMetadata.authorityPrecheck(call.callId, call.name, DispatchPolicyChangedException(listOf("tools")))
    )

    @Test fun freshAuthorizationRestoresTheExactUnsentReadOnceAndPreservesBothReceipts() = runBlocking {
        val journal = Journal()
        val oldBook = journal.book("old-authority", listOf(issue))
        oldBook.observe(originalCall, rejected())
        val original = journal.entries.values.single()
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), oldBook.snapshot().first.values.toList()))
        val rejectedRow = GitHubTestChecklist.rows(oldBook.snapshot().first, listOf(issue), "Test all GitHub tools").single().jsonObject
        assertEquals(JsonPrimitive("REVALIDATION_REQUIRED"), rejectedRow.getValue("readiness").jsonObject["authorization"])

        val resumed = journal.book("current-authority", listOf(issue))
        resumed.restore()
        val retry = requireNotNull(GitHubSelfTestReads.next(listOf(issue), resumed.snapshot().first.values.toList()))
        assertEquals(originalCall.name, retry.name)
        assertEquals(originalCall.arguments, retry.arguments)
        assertNotEquals(originalCall.callId, retry.callId)
        assertNull(original[GitHubPolicyReadRecovery.REVALIDATED])
        assertEquals(original, journal.entries.values.single())

        resumed.observe(retry, AgentToolResult(retry.callId, ToolResultContent.Json(json("""{"comments":[],"has_more":false}""")), false))
        val observations = resumed.snapshot().first.values.toList()
        assertEquals(2, observations.size)
        assertEquals(JsonPrimitive(false), observations.first()["success"])
        assertEquals(JsonPrimitive(true), observations.last()["success"])
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest("current-authority")), observations.last()[GitHubPolicyReadRecovery.AUTHORITY])
        assertEquals(JsonPrimitive(1), observations.last()["previous_attempt_count"])
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), observations))
        assertNull(GitHubSelfTestReads.next(listOf(issue), observations))
        val recoveredRow = GitHubTestChecklist.rows(resumed.snapshot().first, listOf(issue), "Test all GitHub tools").single().jsonObject
        assertEquals(JsonPrimitive("RECOVERED"), recoveredRow["status"])
        assertEquals(JsonPrimitive("AUTHORIZED"), recoveredRow.getValue("readiness").jsonObject["authorization"])

        val restoredAgain = journal.book("current-authority", listOf(issue))
        restoredAgain.restore()
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), restoredAgain.snapshot().first.values.toList()))
        assertFalse(journal.entries.toString().contains("old-authority"))
        assertFalse(journal.entries.toString().contains("current-authority"))
    }

    @Test fun unchangedSettingsAndASecondFailureUnderTheSameRevisionNeverLoop() = runBlocking {
        val journal = Journal()
        journal.book("old", listOf(issue)).observe(originalCall, rejected())
        val unchanged = journal.book("old", listOf(issue))
        unchanged.restore()
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), unchanged.snapshot().first.values.toList()))

        val resumed = journal.book("new", listOf(issue))
        resumed.restore()
        val retry = requireNotNull(GitHubPolicyReadRecovery.next(listOf(issue), resumed.snapshot().first.values.toList()))
        resumed.observe(retry, rejected(retry))
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), resumed.snapshot().first.values.toList()))
        val restored = journal.book("new", listOf(issue))
        restored.restore()
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), restored.snapshot().first.values.toList()))
    }

    @Test fun removedAuthorizationAndChangedArgumentContractsDoNotRecoverTheRead() = runBlocking {
        val journal = Journal()
        journal.book("old", listOf(issue)).observe(originalCall, rejected())
        val changed = issue.copy(inputSchema = json("""{"type":"object","properties":{"action":{"enum":["list"]}},"required":["action"],"additionalProperties":false}"""))
        for (tools in listOf(emptyList(), listOf(changed))) {
            val resumed = journal.book("new", tools)
            resumed.restore()
            val observations = resumed.snapshot().first.values.toList()
            assertEquals(JsonPrimitive(false), observations.single()["verification_eligible"])
            assertNull(observations.single()[GitHubPolicyReadRecovery.REVALIDATED])
            assertNull(GitHubPolicyReadRecovery.next(tools, observations))
        }
    }

    @Test fun legacyPrecheckCanRecoverButForgedSavedRecoveryMarkersCannot() = runBlocking {
        val journal = Journal()
        journal.book(null, listOf(issue)).observe(originalCall, rejected())
        val resumed = journal.book("new", listOf(issue))
        resumed.restore()
        assertTrue(GitHubPolicyReadRecovery.next(listOf(issue), resumed.snapshot().first.values.toList()) != null)
        val id = journal.entries.keys.single()
        journal.entries[id] = JsonObject(
            journal.entries.getValue(id) + mapOf(
                GitHubPolicyReadRecovery.REVALIDATED to JsonPrimitive(GitHubEvidenceBook.digest("forged")),
                GitHubPolicyReadRecovery.AUTHORITY to JsonPrimitive(GitHubEvidenceBook.digest("new"))
            )
        )
        val unchanged = journal.book("new", listOf(issue))
        unchanged.restore()
        assertNull(unchanged.snapshot().first.values.single()[GitHubPolicyReadRecovery.REVALIDATED])
        assertNull(GitHubPolicyReadRecovery.next(listOf(issue), unchanged.snapshot().first.values.toList()))
    }

    @Test fun unknownOrDispatchedOutcomesMutationsAndMalformedCountersCannotEnterReadRecovery() = runBlocking {
        val journal = Journal()
        journal.book("old", listOf(issue)).observe(originalCall, rejected())
        val receipt = journal.entries.values.single()
        val execution = receipt.getValue("execution").jsonObject
        val variants = listOf(
            JsonObject(receipt + ("dispatched" to JsonPrimitive(true))),
            JsonObject(receipt + ("error_code" to JsonPrimitive("TIMEOUT_OUTCOME_UNKNOWN"))),
            JsonObject(receipt + ("execution" to JsonObject(execution + ("requires_reconciliation" to JsonPrimitive(true))))),
            JsonObject(receipt + ("execution" to JsonObject(execution + ("authority_failed_stage" to JsonPrimitive("tool_execution"))))),
            JsonObject(receipt + ("execution" to JsonObject(execution + ("mutation_requests" to JsonPrimitive(1))))),
            JsonObject(receipt + ("execution" to JsonObject(execution + ("read_requests" to JsonPrimitive("unknown"))))),
            JsonObject(receipt + ("execution" to JsonObject(execution + ("read_requests" to JsonPrimitive("0"))))),
            JsonObject(receipt + ("execution" to JsonObject(execution + ("mutation_requests" to JsonPrimitive("+0"))))),
            JsonObject(receipt + ("arguments" to json("""{"action":"create","title":"fixture"}"""))),
            JsonObject(receipt + ("trusted" to JsonPrimitive(false)))
        )
        for (variant in variants) assertNull(variant.toString(), GitHubPolicyReadRecovery.revalidated(variant, GitHubEvidenceBook.digest("new")))
    }
}
