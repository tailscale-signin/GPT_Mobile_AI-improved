package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticArchiveBundleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun finalShareIncludesEveryVerifiedOriginalPartWithoutChangingItsBytesOrHandoff() = runBlocking {
        val directory = temporary.newFolder()
        val results = captureParts(directory)
        val original = results.associate { it.file.name to it.file.readBytes() }
        val bundle = DiagnosticArchiveBundle.prepare(results.last(), listOf(directory))
        assertTrue(bundle.complete)
        val manifest = DiagnosticArchiveBundle.verify(bundle.file)
        assertEquals(JsonPrimitive(true), manifest["dependencyClosureComplete"])
        assertEquals(3, (manifest["parts"] as JsonArray).size)
        assertEquals(manifest, bundle.manifest["archiveBundle"])
        ZipFile(bundle.file).use { zip ->
            original.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(zip.getInputStream(zip.getEntry(name)).readBytes())) }
        }
        // Preparing a bundle is not delivery: the current immutable part stays pending.
        assertNotNull(StagedDiagnosticCapture.pendingArchive(directory, "bundle-fixture", results.last().sessionId))
        StagedDiagnosticCapture.acknowledgeArchiveHandoff(directory, results.last(), retainArchive = true)
        original.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(File(directory, name).readBytes())) }
    }

    @Test fun oldHandoffDestinationCanSupplyAPartEvenIfItsCaptureCopyIsCorrupt() = runBlocking {
        val directory = temporary.newFolder()
        val delivered = temporary.newFolder()
        val results = captureParts(directory)
        results.first().file.copyTo(File(delivered, results.first().file.name))
        results.first().file.writeText("corrupted retained copy")
        val bundle = DiagnosticArchiveBundle.prepare(results.last(), listOf(directory, delivered))
        assertTrue(bundle.complete)
        assertEquals(3, (DiagnosticArchiveBundle.verify(bundle.file)["parts"] as JsonArray).size)
    }

    @Test fun missingOrCorruptEarlierArchiveNeverClaimsCompleteRecoveredHistory() = runBlocking {
        for (corrupt in listOf(false, true)) {
            val directory = temporary.newFolder()
            val results = captureParts(directory)
            if (corrupt) results.first().file.writeText("invalid") else results.first().file.delete()
            val bundle = DiagnosticArchiveBundle.prepare(results.last(), listOf(directory))
            assertFalse(bundle.complete)
            assertTrue(bundle.retryRecommended)
            val manifest = DiagnosticArchiveBundle.verify(bundle.file)
            assertEquals(JsonPrimitive(false), manifest["shareComplete"])
            assertEquals(JsonPrimitive(false), manifest["dependencyClosureComplete"])
            assertEquals(JsonPrimitive(true), manifest["captureCompleted"])
            assertTrue((manifest["missingParts"] as JsonArray).any { it.jsonObject["fileName"] == JsonPrimitive(results.first().file.name) })
            assertEquals(2, (manifest["parts"] as JsonArray).size)
        }
    }

    @Test fun dependencyLimitKeepsTheCurrentPartAndNamesTheUnbundledDependency() = runBlocking {
        val directory = temporary.newFolder()
        val results = captureParts(directory)
        val bundle = DiagnosticArchiveBundle.prepare(results.last(), listOf(directory), maximumParts = 1)
        assertFalse(bundle.complete)
        val manifest = DiagnosticArchiveBundle.verify(bundle.file)
        assertEquals(1, (manifest["parts"] as JsonArray).size)
        assertTrue((manifest["missingParts"] as JsonArray).any { it.jsonObject["reason"] == JsonPrimitive("DEPENDENCY_PART_LIMIT") })
    }

    @Test fun selfCycleAndTotalInflationBudgetCannotProduceACompleteBundle() = runBlocking {
        val directory = temporary.newFolder()
        val results = captureParts(directory)
        val last = results.last()
        val maximum = last.manifest.toString().toByteArray().size.toLong() + 256
        val bounded = DiagnosticArchiveBundle.prepare(last, listOf(directory), maximumBytes = maximum)
        assertFalse(bounded.complete)
        assertEquals(1, (DiagnosticArchiveBundle.verify(bounded.file, maximum)["parts"] as JsonArray).size)
        val previous = last.manifest.getValue("previousArchive").jsonObject
        val cycle = JsonObject(previous + mapOf("fileName" to JsonPrimitive(last.file.name), "archivePart" to JsonPrimitive(3)))
        val modified = rewriteManifest(last, JsonObject(last.manifest + ("previousArchive" to cycle)))
        val bundle = DiagnosticArchiveBundle.prepare(modified, listOf(directory))
        assertFalse(bundle.complete)
        val missing = DiagnosticArchiveBundle.verify(bundle.file)["missingParts"] as JsonArray
        assertTrue(missing.any { it.jsonObject["reason"] == JsonPrimitive("INVALID_ARCHIVE_REFERENCE") })
    }

    @Test fun stageReferenceDigestAndTraversalOutsideTheSessionAreRejected() = runBlocking {
        for (outside in listOf(false, true)) {
            val directory = temporary.newFolder()
            val results = captureParts(directory)
            val last = results.last()
            val stages = (last.manifest["stages"] as JsonArray).map { row ->
                val receipt = row.jsonObject
                val reference = receipt["continuedFrom"] as JsonObject
                val changed = if (outside) {
                    val archive = reference.getValue("archive").jsonObject
                    JsonObject(reference + ("archive" to JsonObject(archive + ("fileName" to JsonPrimitive("../other-session.zip")))))
                } else {
                    JsonObject(reference + ("sha256" to JsonPrimitive("a".repeat(64))))
                }
                JsonObject(receipt + ("continuedFrom" to changed))
            }
            val modified = rewriteManifest(last, JsonObject(last.manifest + ("stages" to JsonArray(stages))))
            val bundle = DiagnosticArchiveBundle.prepare(modified, listOf(directory))
            assertFalse(bundle.complete)
            val missing = DiagnosticArchiveBundle.verify(bundle.file)["missingParts"] as JsonArray
            assertTrue(missing.any { it.jsonObject["reason"] == JsonPrimitive(if (outside) "INVALID_ARCHIVE_REFERENCE" else "STAGE_REFERENCE_MISMATCH") })
        }
    }

    @Test fun cancellationDuringBundleCopyLeavesThePendingPartAndNoPublishedBundle() = runBlocking {
        val directory = temporary.newFolder()
        val results = captureParts(directory)
        var cancelled = false
        try {
            DiagnosticArchiveBundle.prepare(results.last(), listOf(directory), guard = {
                if (directory.listFiles().orEmpty().any { it.name.startsWith("diagnostic-bundle-") && it.name.endsWith(".partial") }) throw CancellationException("capture cleared")
            })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("diagnostic-bundle-") })
        assertNotNull(StagedDiagnosticCapture.pendingArchive(directory, "bundle-fixture", results.last().sessionId))
    }

    @Test fun expiredSessionPrunesItsRetainedOriginalParts() = runBlocking {
        val directory = temporary.newFolder()
        val results = captureParts(directory)
        StagedDiagnosticCapture.acknowledgeArchiveHandoff(directory, results.last(), retainArchive = true)
        val folder = File(directory, "diagnostic-capture-checkpoints/${results.first().sessionId}")
        assertTrue(folder.setLastModified(1))
        StagedDiagnosticCapture.capture(
            directory,
            "fresh",
            listOf(DiagnosticCaptureStage("latest", "latest.jsonl") { it.record("{}") }),
            DiagnosticCaptureOptions(minimumHeadroomBytes = 0)
        )
        assertFalse(folder.exists())
        assertTrue(results.none { it.file.exists() })
    }

    @Test fun finalVerificationRejectsAChangedNestedPartEvenWhenItsZipCrcIsValid() = runBlocking {
        val directory = temporary.newFolder()
        val results = captureParts(directory)
        val bundle = DiagnosticArchiveBundle.prepare(results.last(), listOf(directory))
        val modified = File(directory, "modified-bundle.zip")
        ZipFile(bundle.file).use { source ->
            ZipOutputStream(modified.outputStream()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == results.first().file.name) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    target.write(bytes)
                    target.closeEntry()
                }
            }
        }
        assertTrue(runCatching { DiagnosticArchiveBundle.verify(modified) }.isFailure)
    }

    private suspend fun captureParts(directory: File): List<DiagnosticCaptureResult> {
        val options = DiagnosticCaptureOptions(minimumHeadroomBytes = 0, deferArchiveHandoff = true)
        val stages = listOf(
            DiagnosticCaptureStage("history", "history.jsonl", resumableRecords = true) { sink ->
                val after = sink.resumeCursor?.get("after")?.jsonPrimitive?.int ?: 0
                val next = after + 1
                sink.record("{\"row\":$next}")
                sink.checkpoint(buildJsonObject { put("after", next) })
                if (next < 3) throw IOException("bounded continuation fixture")
            }
        )
        val results = mutableListOf<DiagnosticCaptureResult>()
        repeat(3) {
            val part = StagedDiagnosticCapture.capture(directory, "bundle-fixture", stages, options, results.lastOrNull()?.sessionId)
            results += part
            if (it < 2) StagedDiagnosticCapture.acknowledgeArchiveHandoff(directory, part, retainArchive = true)
        }
        assertTrue(results.last().complete)
        return results
    }

    private fun rewriteManifest(result: DiagnosticCaptureResult, manifest: JsonObject): DiagnosticCaptureResult {
        val changed = File(result.file.parentFile, "changed.zip")
        ZipFile(result.file).use { source ->
            ZipOutputStream(changed.outputStream()).use { output ->
                source.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        output.write(manifest.toString().toByteArray())
                    } else {
                        source.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        assertTrue(changed.renameTo(result.file))
        assertEquals(manifest, StagedDiagnosticCapture.verify(result.file))
        return result.copy(manifest = manifest)
    }
}
