package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.workspace.readWorkspaceDiagnosticPayload
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Ordinary project gate with the same 256 MiB process ceiling as the captured phone. */
class WorkspaceDiagnosticCaptureHeapTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun all859ProtectedRowsResumeAtTheCommittedTailWithoutLeakingOrRepeatingEarlierRows() = runBlocking {
        assumeTrue("Requires the dedicated constrained-heap task", java.lang.Boolean.getBoolean("gptmobile.heapStress"))
        val mib = 1024L * 1024
        assertTrue(Runtime.getRuntime().maxMemory() <= 256 * mib)
        val source = buildJsonObject {
            put("status", "COMPLETED")
            put("body", "private-corpus-canary:" + "x".repeat(448 * 1024))
            put(
                "receipts",
                JsonArray(
                    List(32) {
                        buildJsonObject {
                            put("sequence", it)
                            put("durationMs", 123)
                            put("token", "secret-corpus-canary")
                        }
                    }
                )
            )
        }.toString().encodeToByteArray()
        assertTrue(source.size.toLong() * 859 > Runtime.getRuntime().maxMemory())
        val chunks = source.indices.step(60 * 1024).mapIndexed { index, offset ->
            "workspace-memory-7-$index" to source.copyOfRange(offset, minOf(source.size, offset + 60 * 1024))
        }.toMap()
        val metadata = buildJsonObject {
            put("protectedChunks", JsonArray(chunks.keys.map(::JsonPrimitive)))
            put("digest", DiagnosticRecordChunks.hex(MessageDigest.getInstance("SHA-256").digest(source)))
        }
        val directory = temporary.newFolder("protected")
        var firstPart = true
        var pressured = false
        var authorizations = 0
        val readRows = mutableListOf<Int>()
        val redactor = DiagnosticContentRedactor()
        val reuse = DiagnosticRedactionReuse(redactor::redact)
        val stage = DiagnosticCaptureStage("workspace", "workspace.jsonl", resumableRecords = true) { sink ->
            val after = sink.resumeCursor?.get("after")?.jsonPrimitive?.content?.toInt() ?: 0
            for (id in after + 1..859) {
                if (firstPart && id == 511) pressured = true
                sink.checkBudget()
                val payload = readWorkspaceDiagnosticPayload(
                    7,
                    metadata,
                    1_048_576,
                    sink::checkBudget,
                    {
                        authorizations++
                        true
                    },
                    { chunks.getValue(it).size + 38L },
                    { chunks.getValue(it).copyOf() },
                    { error("Unexpected omission: $it") }
                )
                assertEquals(null, payload.omissionReason)
                val row = buildJsonObject {
                    put("table", "workspace_records")
                    put("rowId", id)
                    put(
                        "fields",
                        buildJsonObject {
                            put("id", "record-$id")
                            put("payload", requireNotNull(payload.payload))
                        }
                    )
                }
                val safe = reuse.workspaceRow(row) { authorizations++ }
                sink.redactedRecord(safe.value, 96 * 1024)
                sink.checkpoint(buildJsonObject { put("after", id) })
                readRows += id
            }
        }
        val options = DiagnosticCaptureOptions(
            minimumHeadroomBytes = 16 * mib,
            memorySampler = { DiagnosticExportMemory.sample().let { if (pressured) it.copy(javaHeadroomBytes = 12 * mib) else it } },
            releaseOptionalCaches = reuse::clear
        )
        val first = StagedDiagnosticCapture.capture(directory, "protected-859", listOf(stage), options)
        assertFalse(first.complete)
        assertEquals((1..510).toList(), readRows)
        val originalDigest = digest(first.file)
        firstPart = false
        pressured = false
        val second = StagedDiagnosticCapture.capture(directory, "protected-859", listOf(stage), options, first.sessionId)
        assertTrue(second.manifest.toString(), second.complete)
        assertEquals((1..859).toList(), readRows)
        assertTrue(authorizations >= 859 * 4)
        assertEquals(originalDigest, digest(first.file))
        val exported = mutableListOf<Int>()
        for (part in listOf(first, second)) {
            assertEquals(part.manifest, StagedDiagnosticCapture.verify(part.file))
            ZipFile(part.file).use { zip ->
                zip.getInputStream(zip.getEntry("workspace.jsonl")).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        assertFalse(line.contains("private-corpus-canary"))
                        assertFalse(line.contains("secret-corpus-canary"))
                        val row = Json.parseToJsonElement(line).jsonObject
                        (row["rowId"] as? JsonPrimitive)?.content?.toInt()?.let(exported::add)
                    }
                }
            }
        }
        assertEquals((1..859).toList(), exported)
        reuse.clear()
        source.fill(0)
        chunks.values.forEach { it.fill(0) }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return DiagnosticRecordChunks.hex(digest.digest())
    }
}
