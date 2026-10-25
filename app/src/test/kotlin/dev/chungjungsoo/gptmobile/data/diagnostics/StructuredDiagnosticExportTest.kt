package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StructuredDiagnosticExportTest {
    @Test fun nestedProviderRecordRemainsAnObjectWhenMessageContainsFieldSyntax() {
        val payload = """{"status":429,"message":"Quota code=DAILY · retryAt=tomorrow, quoted \\\"value=inside\\\"","resetHeaders":{"x-reset":"123"}}"""
        val result = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-24T12:00:00Z W/Provider: PROVIDER_FAILED · provider=LLM7 · record=$payload · durationMs=15")).jsonObject
        val fields = result.getValue("fields").jsonObject
        assertEquals(Json.parseToJsonElement(payload), fields.getValue("record"))
        assertEquals("15", fields.getValue("durationMs").jsonPrimitive.content)
        assertFalse(fields.containsKey("code"))
        assertFalse(fields.containsKey("retryAt"))
    }

    @Test fun arraysRemainStructuredAndTruncatedFieldCountsAreExplicit() {
        val result = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-24T12:00:00Z I/Checks: CHECKED · issues=[\"code=FIRST\",\"code=SECOND\"] · count=2")).jsonObject
        assertEquals(Json.parseToJsonElement("[\"code=FIRST\",\"code=SECOND\"]"), result.getValue("fields").jsonObject.getValue("issues"))
        val many = (1..70).joinToString(" · ") { "field$it=$it" }
        val fields = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-24T12:00:00Z I/Checks: $many")).jsonObject.getValue("fields").jsonObject
        assertEquals("64", fields.getValue("field64").jsonPrimitive.content)
        assertEquals("6", fields.getValue("_fieldCountOmitted").jsonPrimitive.content)
    }

    @Test fun recorderPrefixDoesNotContaminateProcessIdOrEventClassification() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-21T12:00:00Z I/MCP: eventId=abc-123 monoNs=900 bootMs=800 processId=7654 DISCOVERY_NEGOTIATION · outcome=legacy_fallback")).jsonObject
        assertEquals("7654", record.getValue("fields").jsonObject.getValue("processId").jsonPrimitive.content)
        assertFalse(record.getValue("fields").jsonObject.getValue("processId").jsonPrimitive.isString)
        assertEquals("DISCOVERY_NEGOTIATION", record.getValue("eventName").jsonPrimitive.content)
        assertEquals("compatibility_probe", record.getValue("category").jsonPrimitive.content)
        assertEquals("memory_recovery", classifyDiagnostic("I", "Runtime", "eventId=abc monoNs=123 processId=42 OOM_RECOVERY · retained=true").category)
        assertEquals("fatal", classifyDiagnostic("I", "Runtime", "eventId=abc monoNs=123 processId=42 java.lang.OutOfMemoryError: Failed to allocate 1024 bytes").category)
    }

    @Test fun identityDoesNotAbsorbEventLabelAndCountersKeepTheirTypes() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-10T12:00:00Z I/CombinedSynthesis: eventId=abc-123 · SYNTHESIS_QUALITY · candidateUnits=48 · missing=29 · semanticCoverageVerified=false")).jsonObject
        val fields = record.getValue("fields").jsonObject
        assertEquals("abc-123", fields.getValue("eventId").jsonPrimitive.content)
        assertFalse(fields.getValue("candidateUnits").jsonPrimitive.isString)
        assertFalse(fields.getValue("missing").jsonPrimitive.isString)
        assertFalse(fields.getValue("semanticCoverageVerified").jsonPrimitive.isString)
    }

    @Test fun `export keeps status usage source and correlation independently`() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-10T12:00:00Z E/Delegation: Child returned · parentRun=run-1 · status=FAILED · usageTotal=3337 · usageTotalEstimated=true")).jsonObject
        assertEquals("FAILED", record.getValue("fields").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("3337", record.getValue("fields").jsonObject.getValue("usageTotal").jsonPrimitive.content)
        assertEquals("true", record.getValue("fields").jsonObject.getValue("usageTotalEstimated").jsonPrimitive.content)
        assertEquals("run-1", record.getValue("fields").jsonObject.getValue("parentRun").jsonPrimitive.content)
    }

    @Test fun `stack continuations remain exportable and credentials stay redacted`() {
        val record = structuredDiagnosticLine("  at client: Authorization: Bearer secret-value")
        assertEquals("continuation", Json.parseToJsonElement(record).jsonObject.getValue("kind").jsonPrimitive.content)
        assertFalse(record.contains("secret-value"))
    }

    @Test fun `event identity at message start survives repeated capture`() {
        val a = "2026-10-10T12:00:00Z E/Runtime: eventId=abc-123 · Initialization failed"
        val b = "2026-10-10T12:01:00Z E/Runtime: eventId=abc-123 · Initialization failed"
        assertEquals("abc-123", diagnosticEventIdentity(a))
        assertEquals(diagnosticEventIdentity(a), diagnosticEventIdentity(b))
        val continuation = Json.parseToJsonElement(structuredDiagnosticLine("  at runtime()", parentEventId = "abc-123", sequence = 1, captureIdentity = "abc-123:1")).jsonObject
        assertEquals("abc-123", continuation.getValue("parentEventId").jsonPrimitive.content)
        assertEquals("false", continuation.getValue("countAsIssue").jsonPrimitive.content)
    }

    @Test fun nativeHistoryUsesOccurrenceTimeAndIntentDoesNotInventAnExit() {
        val historical = Json.parseToJsonElement("""{"time":1000,"event":"WORKER_TERMINATION_INTENT","process":42,"terminationRequested":true,"workerExitConfirmed":null,"fields":{"workerGenerationId":"worker-7","stage":"cleanup"}}""").jsonObject
        val record = Json.parseToJsonElement(structuredDiagnosticLine(runtimeIncidentLine(historical), "runtime_incident_history", exportedAtMillis = 9000)).jsonObject
        assertEquals("1000", record.getValue("occurredAtMillis").jsonPrimitive.content)
        assertEquals("null", record.getValue("capturedAtMillis").jsonPrimitive.content)
        assertEquals("9000", record.getValue("exportedAtMillis").jsonPrimitive.content)
        assertEquals("42", record.getValue("processId").jsonPrimitive.content)
        assertEquals("worker-7", record.getValue("workerGenerationId").jsonPrimitive.content)
        assertEquals("cleanup", record.getValue("stage").jsonPrimitive.content)
        assertEquals("true", record.getValue("terminationRequested").jsonPrimitive.content)
        assertEquals("null", record.getValue("workerExitConfirmed").jsonPrimitive.content)
        assertEquals("false", record.getValue("countAsIssue").jsonPrimitive.content)
        assertEquals("termination_intent", record.getValue("category").jsonPrimitive.content)
    }

    @Test fun informationalTransportLevelStillPreservesNativeFailureSeverityAndDurationDomain() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-24T10:00:00Z I/Runtime: eventId=load_1 · monoNs=1000 · processId=42 · processName=dev.app:local_inference · LOAD_FAILED · stage=load · nativeOperationId=load_1 · elapsedMs=4677", exportedAtMillis = 2000)).jsonObject
        assertEquals("I", record.getValue("level").jsonPrimitive.content)
        assertEquals("error", record.getValue("severity").jsonPrimitive.content)
        assertEquals("load", record.getValue("stage").jsonPrimitive.content)
        assertEquals("LOAD_FAILED", record.getValue("eventName").jsonPrimitive.content)
        assertEquals("4677", record.getValue("fields").jsonObject.getValue("elapsedMs").jsonPrimitive.content)
        assertEquals("dev.app:local_inference", record.getValue("processName").jsonPrimitive.content)
        assertEquals("true", record.getValue("countAsIssue").jsonPrimitive.content)
    }

    @Test fun providerSnapshotsAndRecoveryKeepTheirExistingCausalEpisodeWithoutLosingFailureIdentity() {
        val failure = dev.chungjungsoo.gptmobile.data.free.FreeProviderFailureRecord(time = 1000, failureId = "failure-1", networkEpisodeId = "episode-7", provider = "TEST", endpoint = "https://example.test", model = "model", profileUid = "p", chatId = 7, stage = "dispatch", message = "DNS unavailable")
        val tracker = DiagnosticIncidentTracker()
        val firstLine = providerFailureLine(failure)
        val first = tracker.accept(firstLine, diagnosticEventIdentity(firstLine), null)
        val secondLine = providerFailureLine(failure.copy(time = 2000, failureId = "failure-2"))
        val second = tracker.accept(secondLine, diagnosticEventIdentity(secondLine), null)
        val recovery = tracker.accept("1970-01-01T00:00:03Z I/Network: CONNECTIVITY_RECOVERED · episode=episode-7 · allHostsVerified=false", "recovery", null)
        assertEquals("network:episode-7", first.id)
        assertEquals(first.id, second.id)
        assertEquals(first.id, recovery.id)
        assertFalse(second.first)
        assertFalse(recovery.first)
        val record = Json.parseToJsonElement(structuredDiagnosticLine(secondLine, "provider_failure", incidentId = second.id, incidentRoot = second.first)).jsonObject
        assertEquals("failure-2", record.getValue("eventId").jsonPrimitive.content)
        assertEquals("false", record.getValue("countAsIssue").jsonPrimitive.content)
        assertEquals("dispatch", record.getValue("stage").jsonPrimitive.content)
    }

    @Test fun multilineHistoricalNativeErrorRemainsOneTimestampedEvent() {
        val historical = Json.parseToJsonElement("""{"time":1000,"event":"LAST_NATIVE_ERROR","originalError":"first line\nsecond line"}""").jsonObject
        val record = Json.parseToJsonElement(structuredDiagnosticLine(runtimeIncidentLine(historical), "runtime_incident_history", exportedAtMillis = 9000)).jsonObject
        assertEquals("event", record.getValue("kind").jsonPrimitive.content)
        assertEquals("1000", record.getValue("occurredAtMillis").jsonPrimitive.content)
        assertEquals("native_operation_failure", record.getValue("category").jsonPrimitive.content)
    }

    @Test fun nativeCurrentAndArchivedJsonKeepOriginalOperationFactsAboveTheLegacyFourKilobyteLimit() {
        val folder = java.nio.file.Files.createTempDirectory("native-incident-export").toFile()
        try {
            fun raw(operation: Int, occurred: Long, backend: String, errors: String, warnings: String) = kotlinx.serialization.json.buildJsonObject {
                put("operationId", kotlinx.serialization.json.JsonPrimitive(operation))
                put("occurredAtMillis", kotlinx.serialization.json.JsonPrimitive(occurred))
                put("stageObservedAtMillis", kotlinx.serialization.json.JsonPrimitive(occurred + 20))
                put("processId", kotlinx.serialization.json.JsonPrimitive(42))
                put("workerGenerationId", kotlinx.serialization.json.JsonPrimitive("worker-one"))
                put("backend", kotlinx.serialization.json.JsonPrimitive(backend))
                put("stage", kotlinx.serialization.json.JsonPrimitive("LOAD_FAILED"))
                put("errors", kotlinx.serialization.json.JsonPrimitive(errors))
                put("warnings", kotlinx.serialization.json.JsonPrimitive(warnings))
            }.toString()
            java.io.File(folder, "native-error.log.history").writeText(raw(1, 1000, "cpu", "Previous CPU operation failed", ""))
            val current = raw(2, 3000, "opencl", "E".repeat(4090), "W".repeat(4090))
            org.junit.Assert.assertTrue(current.length > 4096)
            java.io.File(folder, "native-error.log").writeText(current)
            val records = readNativeIncidentRecords(folder)
            assertEquals(2, records.size)
            assertEquals("cpu", records[0].getValue("backend").jsonPrimitive.content)
            assertEquals("opencl", records[1].getValue("backend").jsonPrimitive.content)
            assertEquals("true", records[0].getValue("archivedOperation").jsonPrimitive.content)
            val record = Json.parseToJsonElement(structuredDiagnosticLine(runtimeIncidentLine(records[1]), "runtime_incident_history", exportedAtMillis = 9000)).jsonObject
            assertEquals("3000", record.getValue("occurredAtMillis").jsonPrimitive.content)
            assertEquals("3020", record.getValue("stageObservedAtMillis").jsonPrimitive.content)
            assertEquals("2", record.getValue("nativeOperationId").jsonPrimitive.content)
            assertEquals("worker-one", record.getValue("workerGenerationId").jsonPrimitive.content)
            assertEquals("9000", record.getValue("exportedAtMillis").jsonPrimitive.content)
            assertEquals("W".repeat(4090), record.getValue("fields").jsonObject.getValue("warnings").jsonPrimitive.content)
            assertEquals("false", record.getValue("truncatedAtExport").jsonPrimitive.content)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun legacyNativeFileDoesNotClaimItsFileTimestampAsOperationTime() {
        val folder = java.nio.file.Files.createTempDirectory("legacy-native-incident").toFile()
        try {
            java.io.File(folder, "native-error.log").writeText("Legacy CPU error without an operation receipt")
            val historical = readNativeIncidentRecords(folder).single()
            val record = Json.parseToJsonElement(structuredDiagnosticLine(runtimeIncidentLine(historical), "runtime_incident_history", exportedAtMillis = 9000)).jsonObject
            assertEquals("null", record.getValue("occurredAtMillis").jsonPrimitive.content)
            assertEquals("unknown_legacy_native_record", record.getValue("timeEvidence").jsonPrimitive.content)
            assertEquals("LEGACY_UNKNOWN", record.getValue("stage").jsonPrimitive.content)
            assertEquals("native_operation_failure", record.getValue("category").jsonPrimitive.content)
        } finally {
            folder.deleteRecursively()
        }
    }
}
