package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionReuseTest {
    @Test fun changingEnvelopeKeepsItsOwnStateWhileReusingProtectedRedactedBody() {
        val redactor = DiagnosticContentRedactor()
        val cache = DiagnosticRedactionReuse(redactor::redact)
        val body = JsonArray(
            List(50) {
                buildJsonObject {
                    put("id", it)
                    put("status", "PASSED")
                    put("token", "never retain this secret")
                }
            }
        )
        fun row(snapshot: Int) = buildJsonObject {
            put("table", "workspace_records")
            put("rowId", snapshot)
            put(
                "fields",
                buildJsonObject {
                    put(
                        "payload",
                        buildJsonObject {
                            put("snapshot_id", snapshot)
                            put("capability_states", body)
                            put("status", if (snapshot == 1) "RUNNING" else "PARTIAL")
                        }
                    )
                }
            )
        }
        var checks = 0
        val guard = {
            checks++
            Unit
        }
        val first = cache.workspaceRow(row(1), guard)
        val second = cache.workspaceRow(row(2), guard)
        assertFalse(first.reused)
        assertTrue(second.reused)
        assertEquals(redactor.redact(row(2)), second.value)
        assertEquals(JsonPrimitive("PARTIAL"), second.value.jsonObject.getValue("fields").jsonObject.getValue("payload").jsonObject["status"])
        assertFalse(second.value.toString().contains("never retain this secret"))
        assertTrue(checks >= 6)
        cache.clear()
        assertFalse(cache.workspaceRow(row(3), guard).reused)
    }

    @Test fun identicalRowsReuseOnlyWithinOneCaptureAndChangedRowsAreRedactedAgain() {
        var transformations = 0
        val row = buildJsonObject {
            put("text", "private conversation")
            put("revision", 1)
        }
        val redactor = DiagnosticContentRedactor()
        val cache = DiagnosticRedactionReuse({
            transformations++
            redactor.redact(it)
        })
        var checks = 0
        val guard = {
            checks++
            Unit
        }
        val first = cache.apply(row, guard)
        val reused = cache.apply(row, guard)
        assertFalse(first.reused)
        assertTrue(reused.reused)
        assertEquals(first.value, reused.value)
        assertFalse(reused.value.toString().contains("private conversation"))
        assertEquals(1, transformations)
        assertEquals(4, checks)
        val changed = buildJsonObject {
            put("text", "private conversation")
            put("revision", 2)
        }
        assertFalse(cache.apply(changed, guard).reused)
        assertEquals(2, transformations)
        cache.clear()
        assertFalse(cache.apply(row, guard).reused)
        assertEquals(3, transformations)
        assertFalse(DiagnosticRedactionReuse(redactor::redact).apply(row, guard).reused)
    }

    @Test fun entryAndByteBudgetsEvictAndOversizedInputsBypassReuse() {
        var calls = 0
        val cache = DiagnosticRedactionReuse({
            calls++
            it
        }, maximumWeightBytes = 1024, maximumInputCharacters = 12, maximumEntries = 1)
        cache.apply(JsonPrimitive("one")) {}
        cache.apply(JsonPrimitive("two")) {}
        assertFalse(cache.apply(JsonPrimitive("one")) {}.reused)
        assertTrue(cache.apply(JsonPrimitive("one")) {}.reused)
        assertEquals(3, calls)
        repeat(2) { assertFalse(cache.apply(JsonPrimitive("oversized source row")) {}.reused) }
        val largeOutput = DiagnosticRedactionReuse({ JsonPrimitive("x".repeat(1000)) }, maximumWeightBytes = 1024)
        repeat(2) { assertFalse(largeOutput.apply(JsonPrimitive("small")) {}.reused) }
    }

    @Test fun authorizationIsRecheckedOnHitsAndBeforeRetainingTransformedOutput() {
        var calls = 0
        val cache = DiagnosticRedactionReuse({
            calls++
            JsonPrimitive("safe")
        })
        val row = JsonPrimitive("private")
        cache.apply(row) {}
        var checks = 0
        val failure = runCatching { cache.apply(row) { if (++checks == 2) error("revoked") } }.exceptionOrNull()
        assertEquals("revoked", failure?.message)
        assertEquals(1, calls)
        checks = 0
        val different = JsonPrimitive("different private value")
        assertTrue(runCatching { cache.apply(different) { if (++checks == 2) error("revoked") } }.isFailure)
        assertFalse(cache.apply(different) {}.reused)
        assertEquals(3, calls)
    }
}
