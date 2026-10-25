package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedClaimInventoryWireTest {
    @Test fun compactRowsRoundTripCompletePropositionsQualifiersAliasesAndOrigins() {
        val shared = "In 2020, a preliminary randomized trial of inhalation in 25 adult patients found no significant benefit."
        val sources = listOf(
            CombinedModelResponse("p", "First", content = "$shared However, evidence remains uncertain.", claimExtractionVersion = 4),
            CombinedModelResponse("q", "Second", content = "$shared However, evidence remains uncertain.", claimExtractionVersion = 4),
            CombinedModelResponse("r", "Third", content = "A distinct example remains relevant.", claimExtractionVersion = 4)
        )
        val claims = CombinedClaimSemantics.records(sources)
        assertTrue(claims.any { it.occurrences.size > 1 && it.qualifiers != CombinedClaimQualifiers() })
        val statuses = claims.associate { it.id to if (it.occurrences.size > 1) "TEXT_SUPPORTED" else "UNVERIFIED" }
        val evidence = claims.associate { it.id to if (it.occurrences.size > 1) listOf("E1", "E2") else emptyList() }
        val rows = claims.map { CombinedClaimInventoryWire.encode(it, statuses.getValue(it.id), evidence.getValue(it.id)) }
        val decoded = CombinedClaimInventoryWire.decode(envelope(rows))
        claims.zip(decoded).forEach { (claim, actual) ->
            assertEquals(claim.id, actual.getValue("id").jsonPrimitive.content)
            assertEquals(claim.proposition, actual.getValue("text").jsonPrimitive.content)
            assertEquals(claim.qualifiers, Json.decodeFromJsonElement(CombinedClaimQualifiers.serializer(), actual.getValue("qualifiers")))
            assertEquals(claim.candidateIds.toList(), actual.getValue("aliases").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(claim.occurrences.map { it.candidateId.substringBefore('.') }.distinct(), actual.getValue("contributors").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                JsonArray(
                    claim.occurrences.map { occurrence ->
                        val span = requireNotNull(occurrence.sourceSpan)
                        JsonArray(listOf(JsonPrimitive(occurrence.candidateId), JsonPrimitive(span.startUtf16), JsonPrimitive(span.endUtf16)))
                    }
                ),
                actual.getValue("source_origins_utf16")
            )
            assertEquals(statuses.getValue(claim.id), actual.getValue("evidence_status").jsonPrimitive.content)
            assertEquals(evidence.getValue(claim.id), actual.getValue("supporting_evidence_ids").jsonArray.map { it.jsonPrimitive.content })
        }
        val defaultClaim = claims.last().copy(qualifiers = CombinedClaimQualifiers())
        val compactDefault = CombinedClaimInventoryWire.encode(defaultClaim, "UNVERIFIED", emptyList())
        assertEquals("Only trailing defaults may be omitted", 3, compactDefault.size)
        val restoredDefault = CombinedClaimInventoryWire.decode(envelope(listOf(compactDefault))).single()
        assertEquals(CombinedClaimQualifiers(), Json.decodeFromJsonElement(CombinedClaimQualifiers.serializer(), restoredDefault.getValue("qualifiers")))
        assertEquals("UNVERIFIED", restoredDefault.getValue("evidence_status").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), restoredDefault.getValue("supporting_evidence_ids"))
    }

    @Test fun missingRangeKeepsItsAliasWithoutInventingASourceLocation() {
        val text = "The town approved 12 homes in 2024."
        val claim = CombinedClaimSemantics.records(listOf(CombinedModelResponse("p", "First", content = text), CombinedModelResponse("q", "Second", content = text))).single()
        val partial = claim.copy(occurrences = claim.occurrences.mapIndexed { index, origin -> if (index == 0) origin.copy(sourceSpan = null) else origin })
        val row = CombinedClaimInventoryWire.encode(partial, "UNVERIFIED", emptyList())
        val decoded = CombinedClaimInventoryWire.decode(envelope(listOf(row))).single()
        assertEquals(partial.candidateIds.toList(), decoded.getValue("aliases").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(listOf(JsonPrimitive(partial.id))), decoded.getValue("source_origins_utf16").jsonArray.first())
        assertEquals(3, decoded.getValue("source_origins_utf16").jsonArray.last().jsonArray.size)
        assertFalse("source_ranges_utf16" in decoded)
    }

    @Test fun legacyObjectsAndOrdinaryPromptsKeepCoverageAndBudgetBehavior() {
        val text = "Distinct relevant detail. ".repeat(200)
        val record = CombinedClaimRecord("C1.1", "semantic", text, occurrences = listOf(CombinedClaimOccurrence("C1.1", "p", "digest")))
        val rowPrompt = envelope(listOf(CombinedClaimInventoryWire.encode(record, "UNVERIFIED", emptyList())))
        val legacyRow = buildJsonObject {
            put("id", record.id)
            put("text", text)
            put("canonical_id", "retained-legacy-identity")
            put("aliases", JsonArray(listOf(JsonPrimitive(record.id))))
        }
        val legacy = buildJsonObject {
            put("original_request", "Explain the findings")
            put("claim_inventory", JsonArray(listOf(legacyRow)))
        }
        assertEquals(listOf(legacyRow), CombinedClaimInventoryWire.decode(legacy))
        assertEquals(CombinedCoverage(legacy.toString()).manifest(text), CombinedCoverage(rowPrompt.toString()).manifest(text))
        val expectedBudget = CombinedResponseBudget.continuationLimit(legacy.toString(), 128, null)
        assertTrue(expectedBudget > 1)
        assertEquals(expectedBudget, CombinedResponseBudget.continuationLimit(rowPrompt.toString(), 128, null))
        val ordinary = buildJsonObject { put("original_request", "Explain the findings") }
        assertTrue(CombinedClaimInventoryWire.decode(ordinary).isEmpty())
        assertEquals(0, CombinedCoverage(ordinary.toString()).manifest("").getValue("totalItems").jsonPrimitive.content.toInt())
        assertEquals(1, CombinedResponseBudget.continuationLimit(ordinary.toString(), 128, null))
        CombinedClaimInventoryWire.validatePrompt("Complete frozen legacy prompt")
        assertEquals(0, CombinedCoverage("Complete frozen legacy prompt").manifest("").getValue("totalItems").jsonPrimitive.content.toInt())
        assertEquals(1, CombinedResponseBudget.continuationLimit("Complete frozen legacy prompt", 128, null))
    }

    @Test fun unsupportedOrMalformedDeclaredRowsFailEveryInventoryConsumer() {
        val valid = listOf<JsonElement>(JsonPrimitive("C1.1"), JsonPrimitive("Complete readable claim."), JsonArray(listOf(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(24))))))
        fun withOrigins(vararg origins: JsonArray) = JsonArray(valid.take(2) + listOf(JsonArray(origins.toList())))
        val invalidRows = listOf(
            JsonArray(valid.take(2)),
            JsonArray(valid + JsonPrimitive("not qualifiers")),
            JsonArray(valid + JsonObject(mapOf("unknown_qualification" to JsonPrimitive(true)))),
            JsonArray(valid.mapIndexed { index, value -> if (index == 0) JsonPrimitive(1) else value }),
            JsonArray(valid.mapIndexed { index, value -> if (index == 1) JsonPrimitive(1) else value }),
            withOrigins(),
            withOrigins(JsonArray(listOf(JsonPrimitive("C2.1"), JsonPrimitive(0), JsonPrimitive(24)))),
            withOrigins(JsonArray(listOf(JsonPrimitive(-1), JsonPrimitive(24)))),
            withOrigins(JsonArray(listOf(JsonPrimitive(24), JsonPrimitive(24)))),
            withOrigins(JsonArray(listOf(JsonPrimitive("0"), JsonPrimitive("24")))),
            withOrigins(JsonArray(emptyList()), JsonArray(emptyList())),
            JsonArray(valid + listOf(JsonObject(emptyMap()), JsonPrimitive("UNVERIFIED"), JsonArray(listOf(JsonPrimitive(7)))))
        )
        invalidRows.forEach { reject(envelope(listOf(it))) }
        reject(envelope(listOf(JsonObject(emptyMap()))))
        reject(envelope(listOf(JsonArray(valid), JsonArray(valid))))
        reject(envelope(listOf(JsonArray(valid)), JsonObject(CombinedClaimInventoryWire.schema + ("version" to JsonPrimitive(2)))))
        reject(envelope(listOf(JsonArray(valid)), JsonObject(CombinedClaimInventoryWire.schema + ("columns" to JsonArray(listOf(JsonPrimitive("unknown")))))))
        reject(envelope(listOf(JsonArray(valid)), JsonObject(CombinedClaimInventoryWire.schema + ("defaults" to JsonObject(emptyMap())))))
        reject(JsonObject(envelope(listOf(JsonArray(valid))) - "claim_inventory"))
        reject(JsonObject(envelope(listOf(JsonArray(valid))) - "claim_inventory_schema"))
        listOf("{\"claim_inventory_schema\":", "[${envelope(listOf(JsonArray(valid)))}]", "\"claim_inventory_schema\"", "[{\"\\u0063laim_inventory_schema\":{\"version\":99},\"claim_inventory\":[]}]").forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) { CombinedClaimInventoryWire.validatePrompt(malformed) }
            assertThrows(IllegalArgumentException::class.java) { CombinedCoverage(malformed) }
            assertThrows(IllegalArgumentException::class.java) { CombinedResponseBudget.continuationLimit(malformed, 128, null) }
        }
    }

    @Test fun malformedQualifierDiagnosticsDoNotExposeThePrivateClaimOrInput() {
        val sentinel = "private-claim-qualifier-sentinel"
        val row = JsonArray(
            listOf(
                JsonPrimitive("C1.1"),
                JsonPrimitive("Private text: $sentinel"),
                JsonArray(listOf(JsonArray(emptyList()))),
                JsonObject(mapOf("unknown_qualification" to JsonPrimitive(sentinel)))
            )
        )
        val prompt = envelope(listOf(row))
        val snapshot = CombinedInputSnapshot(
            version = 6, userMessage = MessageV2(platformType = null, content = "Explain the findings"), history = emptyList(), replies = emptyList(), sources = emptyList(),
            prompt = prompt.toString(), profileUid = "p", model = "model", systemPrompt = "", configKey = "key", snapshotId = "private-schema-recovery"
        ).seal()
        val failures = listOf(
            assertThrows(IllegalArgumentException::class.java) { CombinedClaimInventoryWire.decode(prompt) },
            assertThrows(IllegalArgumentException::class.java) { CombinedCoverage(prompt.toString()) },
            assertThrows(IllegalArgumentException::class.java) { CombinedResponseBudget.continuationLimit(prompt.toString(), 128, null) },
            assertThrows(IllegalArgumentException::class.java) { snapshot.bindAcceptedStage() }
        )
        failures.forEach { failure ->
            assertEquals("Invalid Combined claim qualifiers", failure.message)
            assertFalse(failure.stackTraceToString().contains(sentinel))
            assertEquals(null, failure.cause)
        }
    }

    private fun envelope(rows: List<JsonElement>, schema: JsonObject = CombinedClaimInventoryWire.schema): JsonObject = buildJsonObject {
        put("original_request", "Explain the findings")
        put("claim_inventory_schema", schema)
        put("claim_inventory", JsonArray(rows))
    }

    private fun reject(envelope: JsonObject) {
        assertThrows(IllegalArgumentException::class.java) { CombinedClaimInventoryWire.decode(envelope) }
        assertThrows(IllegalArgumentException::class.java) { CombinedCoverage(envelope.toString()) }
        assertThrows(IllegalArgumentException::class.java) { CombinedResponseBudget.continuationLimit(envelope.toString(), 128, null) }
    }
}
