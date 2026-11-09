package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceDiagnostics
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubRunAcceptance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceDiagnosticRoundTripTest {
    @Test fun allTypedCriteriaSurviveStorageAndStructuredExportWithTheirOriginalDigests() {
        val ledger = buildJsonObject {
            put(
                "requirements",
                JsonArray(
                    (1..316).map { index ->
                        buildJsonObject {
                            put("id", "requirement-$index")
                            put("kind", "REQUIREMENT")
                            put("criterion", "Inspect requested files")
                            put("required", index % 2 == 0)
                            put("state", if (index % 3 == 0) "PASS" else "NOT_TESTED")
                            put(
                                "readiness",
                                buildJsonObject {
                                    put("authorization", "AUTHORIZED")
                                    put("availability", "AVAILABLE")
                                }
                            )
                            put("receipt_ids", JsonArray(listOf(JsonPrimitive("receipt-$index"))))
                        }
                    }
                )
            )
        }
        val pages = GitHubAcceptanceDiagnostics.pages(ledger)
        val originals = pages.flatMap { (it.getValue("criteria") as JsonArray).map { item -> item.jsonObject } }.associateBy { it["ordinal"] }
        val assessment = GitHubRunAcceptance.Assessment("PARTIAL", "evidence", 158, 52, 0, pages.first(), pages)
        val lines = GitHubRunAcceptance.diagnosticLines(assessment)
        assertEquals(317, lines.size)
        val rows = lines.mapNotNull { line ->
            val persisted = diagnosticMessageForStorage(line, "GitHubAcceptance")
            val exported = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-09T12:00:00Z I/GitHubAcceptance: $persisted")).jsonObject
            val typed = exported.getValue("acceptance").jsonObject
            val row = typed["criterion"] as? JsonObject ?: return@mapNotNull null
            assertEquals(originals[row["ordinal"]], row)
            assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(row.toString())), typed["criterion_sha256"])
            assertEquals(JsonPrimitive("AUTHORIZED"), row["authorization"])
            assertTrue(row["required"] in setOf(JsonPrimitive(true), JsonPrimitive(false)))
            row
        }
        assertEquals(316, rows.size)
        assertEquals(316, rows.map { it["ordinal"] }.distinct().size)
    }

    @Test fun semanticAuthorizationNeverExemptsCredentialValuesOrOrdinaryHttpHeaders() {
        val row = buildJsonObject {
            put("kind", "REQUIREMENT")
            put("state", "BLOCKED")
            put("required", true)
            put("id_sha256", "a".repeat(64))
            put("evidence_receipt_digests", JsonArray(emptyList()))
            put("authorization", "AUTHORIZED")
        }
        assertEquals(row, redactChatDebugData(row))
        assertEquals(row.toString(), redactLogMessage(row.toString()))
        val credential = JsonObject(row + ("authorization" to JsonPrimitive("Bearer private-secret")))
        assertFalse(redactLogMessage(credential.toString()).contains("private-secret"))
        assertFalse(redactChatDebugData(credential).toString().contains("private-secret"))
        assertEquals(JsonPrimitive("[redacted]"), redactChatDebugData(row, listOf("AUTHORIZED")).jsonObject["authorization"])
        assertEquals("""{"authorization":"[redacted]"}""", redactLogMessage("""{"authorization":"AUTHORIZED"}"""))
    }
}
