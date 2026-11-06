package dev.chungjungsoo.gptmobile.data.network.gateway

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GatewayDiagnosticSpoolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val boot = "b".repeat(32)
    private val now = 1_800_000_000_000L
    private fun fields(error: Boolean = false) = buildJsonObject {
        put("stage", "transport")
        put("severity", if (error) "error" else "info")
        put("error_code", if (error) "UNKNOWN_CAUSE" else "NONE")
    }
    private fun file() = File(temporary.root, "capture.jsonl")
    private fun spool() = GatewayDiagnosticSpool(file(), boot, { now })
    private fun append(spool: GatewayDiagnosticSpool, count: Int) = spool.append(List(count) { now + it to fields() })

    private fun ack(spool: GatewayDiagnosticSpool, through: Long) = buildJsonObject {
        put("schema_version", 2)
        put("session_id", spool.sessionId)
        put("storage_state", "committed")
        put(
            "watermarks",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("origin_device_pseudonym", spool.originId)
                        put("origin_boot_id", boot)
                        put("accounted_through", through)
                    }
                )
            )
        )
    }

    private fun remote(sequence: Long, cursor: Long = sequence): JsonObject {
        val origin = "c".repeat(32)
        val originBoot = "d".repeat(32)
        return buildJsonObject {
            put("schema_version", 2)
            put("event_id", GatewayDiagnosticEvent.identity(origin, originBoot, sequence))
            put("origin_device_pseudonym", origin)
            put("origin_boot_id", originBoot)
            put("origin_sequence", sequence)
            put("occurred_at_utc", now + sequence)
            put("observed_at_utc", now + cursor)
            put("gateway_ingest_sequence", cursor)
            put("owner", "gateway")
            put("severity", "info")
            put("stage", "response")
        }
    }

    private fun page(spool: GatewayDiagnosticSpool, rows: List<JsonObject>, cursor: Long) = buildJsonObject {
        put("schema_version", 2)
        put("session_id", spool.sessionId)
        put("events", JsonArray(rows))
        put("next_cursor", cursor)
        put("last_ingest_sequence", cursor)
    }

    private fun export(spool: GatewayDiagnosticSpool): Pair<JsonObject, List<JsonObject>> {
        val destination = temporary.newFile("export-${System.nanoTime()}.zip")
        spool.export(destination)
        return ZipFile(destination).use { zip ->
            val content = zip.getInputStream(zip.getEntry("events.jsonl")).readBytes()
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()) as JsonObject
            val record = (manifest["files"] as JsonArray).single() as JsonObject
            val rows = content.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).map { Json.parseToJsonElement(it) as JsonObject }.toList()
            assertEquals(content.size.toLong(), record.diagnosticLong("bytes"))
            assertEquals(rows.size.toLong(), record.diagnosticLong("records"))
            assertEquals(MessageDigest.getInstance("SHA-256").digest(content).diagnosticHex(), record.diagnosticText("sha256"))
            assertTrue(rows.size <= 5000)
            assertTrue(rows.all { GatewayDiagnosticEvent.bytes(it) <= 2048 })
            assertEquals(rows.size, rows.map { it.diagnosticText("event_id") }.distinct().size)
            manifest to rows
        }
    }

    @Test fun event5001EvictsOneAndPersistsUnsentGapAcrossRestart() {
        var spool = spool()
        append(spool, 5001)
        val status = spool.status()
        assertEquals(5000, status.retained)
        assertEquals(1L, status.evicted)
        assertEquals(1L, status.dropped)
        val oldSession = spool.sessionId
        spool = spool()
        assertEquals(oldSession, spool.sessionId)
        val gap = (spool.batch()["gaps"] as JsonArray).single() as JsonObject
        assertEquals(spool.originId, gap.diagnosticText("origin_device_pseudonym"))
        assertEquals(1L, gap.diagnosticLong("first"))
        assertEquals(1L, gap.diagnosticLong("last"))
        val (manifest, events) = export(spool)
        assertEquals(2L, events.first().diagnosticLong("origin_sequence"))
        assertEquals(5001L, events.last().diagnosticLong("origin_sequence"))
        assertEquals(1L, manifest.diagnosticLong("evicted_count"))
        assertEquals(JsonPrimitive(false), manifest["vendor_log_available"])
    }

    @Test fun durableAckAndSequenceAllocationAreIndependentOfRotatingRows() {
        var spool = spool()
        append(spool, 5001)
        spool.acknowledge(ack(spool, 5001))
        assertEquals(0, spool.status().pending)
        spool = spool()
        append(spool, 1)
        assertEquals(1, spool.status().pending)
        val event = (spool.batch()["events"] as JsonArray).single() as JsonObject
        assertEquals(5002L, event.diagnosticLong("origin_sequence"))
        spool.acknowledge(ack(spool, 5000))
        assertEquals(1, spool.status().pending)
    }

    @Test fun imported5001EventsRespectCombinedCapAndNeverEchoOrReinsertOldPages() {
        var spool = spool()
        append(spool, 1)
        for (start in 1..5001 step 64) {
            val end = minOf(5001, start + 63)
            spool.importPage(page(spool, (start..end).map { remote(it.toLong()) }, end.toLong()))
        }
        assertEquals(5000, spool.status().retained)
        assertEquals(2L, spool.status().evicted)
        assertTrue((spool.batch()["events"] as JsonArray).isEmpty())
        assertEquals(1, (spool.batch()["gaps"] as JsonArray).size)
        spool = spool()
        spool.importPage(page(spool, listOf(remote(1)), 1))
        assertEquals(5001L, spool.cursor)
        val (_, rows) = export(spool)
        assertEquals(2L, rows.first().diagnosticLong("origin_sequence"))
        assertEquals(5001L, rows.last().diagnosticLong("origin_sequence"))
    }

    @Test fun disconnectAfterGatewayCommitReplaysSameIdsAndResumesWithoutDuplicates() = runTest {
        var spool = spool()
        append(spool, 2)
        val committed = mutableSetOf<String>()
        val sent = mutableListOf<List<String>>()
        var loseResponse = true
        val transport = object : GatewayDiagnosticTransport {
            override suspend fun post(batch: JsonObject): JsonObject {
                val ids = (batch["events"] as JsonArray).map { (it as JsonObject).diagnosticText("event_id")!! }
                sent += ids
                committed += ids
                if (loseResponse) throw IOException("Bearer private-token in a network exception")
                return ack(spool, 2)
            }
            override suspend fun read(session: String, after: Long, excludeOrigin: String) = page(spool, listOf(remote(1, 3)), 3)
        }
        try {
            GatewayDiagnosticSync(spool, transport, { true }).once()
            fail("Expected offline failure")
        } catch (_: IOException) { }
        assertEquals(2, spool.status().pending)
        spool = spool()
        loseResponse = false
        GatewayDiagnosticSync(spool, transport, { true }).once()
        assertEquals(sent[0], sent[1])
        assertEquals(2, committed.size)
        assertEquals(0, spool.status().pending)
        assertEquals(3, spool.status().retained)
        assertEquals(3L, spool.cursor)
        assertFalse(file().readText().contains("private-token"))
    }

    @Test fun privacyChangeAtPhysicalExchangeStopsAckPersistenceAndRemoteFetch() = runTest {
        val spool = spool()
        append(spool, 1)
        val before = file().readText()
        var permitted = true
        var reads = 0
        val transport = object : GatewayDiagnosticTransport {
            override suspend fun post(batch: JsonObject): JsonObject {
                permitted = false
                return ack(spool, 1)
            }
            override suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject {
                reads++
                return page(spool, emptyList(), 0)
            }
        }
        GatewayDiagnosticSync(spool, transport, { permitted }).once()
        assertEquals(0, reads)
        assertEquals(before, file().readText())
        assertEquals(1, spool.status().pending)
    }

    @Test fun diskFailureRetainsPreviousGenerationAndCannotAdvanceReceiptOrLeakException() {
        var writesFail = false
        val file = file()
        val spool = GatewayDiagnosticSpool(file, boot, { now }) { target, header, rows ->
            if (writesFail) throw IOException("disk full /private/path?token=secret")
            target.parentFile.mkdirs()
            target.writeText(header + "\n" + rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n"))
        }
        append(spool, 1)
        val before = file.readBytes()
        writesFail = true
        try {
            spool.acknowledge(ack(spool, 1))
            fail("Expected disk failure")
        } catch (failure: IOException) {
            assertEquals("DEBUG_STORAGE_UNAVAILABLE", failure.message)
        }
        assertEquals(1, spool.status().pending)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals("DEBUG_STORAGE_UNAVAILABLE", spool.status().storageError)
        writesFail = false
        spool.acknowledge(ack(spool, 1))
        assertEquals(0, spool.status().pending)
        assertEquals(null, spool.status().storageError)
    }

    @Test fun secretsNestedAttributesAndCrLfAreExcludedBeforePersistentQueueData() {
        val spool = spool()
        val malicious = buildJsonObject {
            put("stage", "transport")
            put("severity", "error")
            put("exception", "Bearer secret\r\nforged event")
            put("request_id", "https://private/?token=secret")
            put(
                "bounded_attributes",
                buildJsonObject {
                    put("status", 500)
                    put("token", "secret")
                    put("nested", buildJsonObject { put("password", "secret") })
                    put("vendor_log_available", true)
                }
            )
        }
        spool.append(listOf(now to malicious))
        val stored = file().readText()
        assertFalse(stored.contains("secret"))
        assertFalse(stored.contains("private"))
        assertEquals(2, stored.lineSequence().filter(String::isNotEmpty).count())
        val (manifest, rows) = export(spool)
        assertEquals(1, rows.size)
        assertEquals(JsonPrimitive(false), (rows.single()["bounded_attributes"] as JsonObject)["vendor_log_available"])
        assertEquals(JsonPrimitive(false), manifest["complete"])
    }

    @Test fun interruptedSnapshotAndCorruptRecordExposeGapWithoutIdentityReuse() {
        var spool = spool()
        append(spool, 3)
        File(temporary.root, "capture.jsonl.tmp").writeText("incomplete private temporary data")
        val original = file().readLines()
        file().writeText(original.dropLast(1).joinToString("\n", postfix = "\n"))
        spool = spool()
        val gap = (spool.batch()["gaps"] as JsonArray).single() as JsonObject
        assertEquals(3L, gap.diagnosticLong("first"))
        assertEquals("CAPTURE_INTERRUPTED", gap.diagnosticText("reason"))
        append(spool, 1)
        val events = (spool.batch()["events"] as JsonArray).map { (it as JsonObject).diagnosticLong("origin_sequence") }
        assertEquals(listOf(1L, 2L, 4L), events)
        assertFalse(file().readText().contains("private"))
    }

    @Test fun simultaneousExportHasConsistentFileCountsAndHashesWhileWriterContinues() {
        val spool = spool()
        append(spool, 100)
        val executor = Executors.newSingleThreadExecutor()
        val start = CountDownLatch(1)
        try {
            val writer = executor.submit {
                start.await()
                append(spool, 100)
            }
            start.countDown()
            repeat(3) {
                val (manifest, rows) = export(spool)
                assertEquals(rows.size.toLong(), manifest.diagnosticLong("retained_count"))
                assertEquals(rows.size.toLong(), rows.last().diagnosticLong("origin_sequence"))
            }
            writer.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun processRestartUsesDistinctClockDomainAndCaptureDurationIsBounded() {
        var spool = spool()
        append(spool, 1)
        spool = GatewayDiagnosticSpool(file(), "e".repeat(32), { now })
        append(spool, 1)
        val events = (spool.batch()["events"] as JsonArray).filterIsInstance<JsonObject>()
        assertNotEquals(events[0].diagnosticText("origin_boot_id"), events[1].diagnosticText("origin_boot_id"))
        assertEquals(listOf(1L, 1L), events.map { it.diagnosticLong("origin_sequence") })
        val before = Files.readAllBytes(file().toPath())
        spool = GatewayDiagnosticSpool(file(), boot, { now + GatewayDiagnosticEvent.CAPTURE_DURATION_MILLIS + 1 })
        try {
            append(spool, 1)
            fail("Expected bounded capture duration")
        } catch (_: IOException) { }
        assertTrue(before.contentEquals(Files.readAllBytes(file().toPath())))
    }

    @Test fun precheckAndUncertainDispatchHaveTruthfulTaxonomy() {
        val projected = GatewayDiagnosticEvent.fields(
            buildJsonObject {
                put("error_code", "PRECHECK_REJECTED")
                put("dispatch_state", "dispatched")
            }
        )
        assertEquals("not_dispatched", projected.diagnosticText("dispatch_state"))
        val uncertain = GatewayDiagnosticEvent.fields(
            buildJsonObject {
                put("error_code", "DISPATCH_UNCERTAIN")
                put("retry_class", "safe_read")
            }
        )
        assertEquals("unknown", uncertain.diagnosticText("dispatch_state"))
        assertEquals("reconcile", uncertain.diagnosticText("retry_class"))
    }
}
