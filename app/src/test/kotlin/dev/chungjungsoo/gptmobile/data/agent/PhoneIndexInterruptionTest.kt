package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneIndexInterruptionTest {
    private val scope = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")
    private val arguments = buildJsonObject {
        put("action", "index")
        put("map_source", "phone")
    }
    private val call = ProviderEvent.ToolCall("owned", "repository_graph__connection", arguments)
    private val payload = buildJsonObject {
        put("action", "index")
        put("budget_bytes", 16000)
    }

    @Test fun onlyTheExactPhoneIndexInvocationCanBeginAWitness() {
        val observer = PhoneIndexInterruption(call)
        assertNull(observer.begin("other", call.name, arguments, scope, "account"))
        assertNull(observer.begin(call.callId, "repository_graph__other", arguments, scope, "account"))
        assertNull(observer.begin(call.callId, call.name, JsonObject(arguments + ("query" to JsonPrimitive("other"))), scope, "account"))
        for (changed in listOf(
            call.copy(arguments = JsonObject(arguments + ("map_source" to JsonPrimitive("gateway")))),
            call.copy(arguments = JsonObject(arguments + ("action" to JsonPrimitive("status")))),
            call.copy(name = "github_write__connection")
        )) {
            assertNull(PhoneIndexInterruption(changed).begin(changed.callId, changed.name, changed.arguments, scope, "account"))
        }
        assertNull(observer.begin(call.callId, call.name, arguments, null, "account"))
        assertNull(observer.begin(call.callId, call.name, arguments, scope, ""))
        assertNull(observer.close())
    }

    @Test fun accountProfileRepositoryRevisionAndPayloadMismatchesCannotQualify() {
        val changedScopes = listOf(
            scope.copy(connectionUid = "other"),
            scope.copy(mainProfileUid = "other"),
            scope.copy(owner = "other"),
            scope.copy(repo = "other"),
            scope.copy(ref = "feature"),
            scope.copy(commitSha = "b".repeat(40))
        )
        for (observed in changedScopes) {
            val observer = PhoneIndexInterruption(call)
            val attempt = requireNotNull(observer.begin(call.callId, call.name, arguments, scope, "account"))
            attempt.admit(observed, "account", payload)
            attempt.cancelledAfterUnwind(accounting())
            assertNull("A different selected scope must not supply this witness: $observed", observer.close())
        }
        for ((account, actual) in listOf("other-account" to payload, "account" to JsonObject(payload + ("index_cursor" to JsonPrimitive("other"))))) {
            val observer = PhoneIndexInterruption(call)
            val attempt = requireNotNull(observer.begin(call.callId, call.name, arguments, scope, "account"))
            attempt.admit(scope, account, actual)
            attempt.cancelledAfterUnwind(accounting())
            assertNull(observer.close())
        }
    }

    @Test fun admissionAndStructuredUnwindAreBothRequired() {
        val observer = PhoneIndexInterruption(call)
        val attempt = requireNotNull(observer.begin(call.callId, call.name, arguments, scope, "account"))
        attempt.cancelledAfterUnwind(accounting())
        assertNull(observer.close())

        val admittedOnly = PhoneIndexInterruption(call)
        requireNotNull(admittedOnly.begin(call.callId, call.name, arguments, scope, "account")).admit(scope, "account", payload)
        assertNull(admittedOnly.close())
    }

    @Test fun lateCompetingAndRepeatedAdmissionsCannotReuseAWitness() {
        val late = PhoneIndexInterruption(call)
        val attempt = requireNotNull(late.begin(call.callId, call.name, arguments, scope, "account"))
        attempt.admit(scope, "account", payload)
        assertNull(late.close())
        attempt.cancelledAfterUnwind(accounting())
        assertNull(late.close())

        val competing = PhoneIndexInterruption(call)
        val first = requireNotNull(competing.begin(call.callId, call.name, arguments, scope, "account"))
        first.admit(scope, "account", payload)
        first.cancelledAfterUnwind(accounting())
        assertNull(competing.begin(call.callId, call.name, arguments, scope.copy(mainProfileUid = "other"), "account"))
        assertNull(competing.close())

        val repeated = PhoneIndexInterruption(call)
        val duplicate = requireNotNull(repeated.begin(call.callId, call.name, arguments, scope, "account"))
        duplicate.admit(scope, "account", payload)
        duplicate.admit(scope, "account", payload)
        duplicate.cancelledAfterUnwind(accounting())
        assertNull(repeated.close())
        assertNull(PhoneIndexInterruption(call.copy(callId = "next")).close())
    }

    @Test fun observedMutationNeverBecomesAKnownLocalInterruption() = runBlocking {
        val observer = PhoneIndexInterruption(call)
        val attempt = requireNotNull(observer.begin(call.callId, call.name, arguments, scope, "account"))
        attempt.admit(scope, "account", payload)
        val accounting = accounting()
        withContext(accounting) { GitHubDispatchAccounting.started(true, "/repos/owner/repo/git/refs") }
        attempt.cancelledAfterUnwind(accounting)
        assertNull(observer.close())
    }

    @Test fun witnessRetainsKnownFailureWithoutGrantingReplayOrRemoteSettlement() {
        val observer = PhoneIndexInterruption(call)
        val attempt = requireNotNull(observer.begin(call.callId, call.name, arguments, scope, "account"))
        attempt.admit(scope, "account", payload)
        attempt.cancelledAfterUnwind(accounting())
        val result = requireNotNull(observer.close())
        assertEquals("PHONE_INDEX_INTERRUPTED", result.errorCode)
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("request_replay_allowed"))
        assertNotNull(result.executionReceipt?.get("scope_hash"))
        assertNull(result.executionReceipt?.get("graph_index_attempt_hash"))
        assertNull(result.executionReceipt?.get("confirmed_index_attempt_hash"))
    }

    private fun accounting() = GitHubDispatchAccounting(record = {})
}
