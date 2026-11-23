package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhoneIndexWitnessReuseTest {
    private val scope = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")
    private val arguments = buildJsonObject {
        put("action", "index")
        put("map_source", "phone")
    }
    private val call = ProviderEvent.ToolCall("owned", "repository_graph__connection", arguments)

    @Test
    fun reuseRequiresExactInvocationAndPhysicalOwner() {
        val witness = PhoneIndexInterruption(call)
        assertSame(witness, witness.reuseFor(call, samePhysicalOwner = true))
        assertNull(witness.reuseFor(call, samePhysicalOwner = false))
        for (changed in listOf(
            call.copy(callId = "another-call"),
            call.copy(name = "repository_graph__other-connection"),
            call.copy(arguments = JsonObject(arguments + ("index_cursor" to JsonPrimitive("another-attempt")))),
            call.copy(arguments = JsonObject(arguments + ("map_source" to JsonPrimitive("gateway"))))
        )) {
            assertNull("Different invocation must not inherit a witness: $changed", witness.reuseFor(changed, samePhysicalOwner = true))
        }
        assertNotNull(witness.begin(call.callId, call.name, arguments, scope, "account"))
        assertSame(witness, witness.reuseFor(call, samePhysicalOwner = true))
    }

    @Test
    fun closedOrConflictingWitnessCannotBeShared() {
        val closed = PhoneIndexInterruption(call)
        assertNotNull(closed.begin(call.callId, call.name, arguments, scope, "account"))
        assertNull(closed.close())
        assertNull(closed.reuseFor(call, samePhysicalOwner = true))

        val conflicting = PhoneIndexInterruption(call)
        assertNotNull(conflicting.begin(call.callId, call.name, arguments, scope, "account"))
        assertNull(conflicting.begin(call.callId, call.name, arguments, scope, "account"))
        assertNull(conflicting.reuseFor(call, samePhysicalOwner = true))
        assertNull(conflicting.close())
    }
}
