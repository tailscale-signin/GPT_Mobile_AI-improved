package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchmarkExportDestinationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingDocumentPickerIsReportedWithoutEscapingTheClick() {
        val unavailable = IllegalStateException("No document picker activity")
        var reported: Exception? = null
        assertFalse(launchBenchmarkExportDestination("benchmarks.json", { throw unavailable }, { reported = it }))
        assertEquals(unavailable, reported)
    }

    @Test fun pickerReceivesOnlyTheSuggestedFileNameAndCanBeLaunchedAgainAfterFailure() {
        var reported: Exception? = null
        assertFalse(launchBenchmarkExportDestination("benchmarks.json", { throw SecurityException("denied") }, { reported = it }))
        assertTrue(reported is SecurityException)
        var requested: String? = null
        reported = null
        assertTrue(launchBenchmarkExportDestination("benchmarks.json", { requested = it }, { reported = it }))
        assertEquals("benchmarks.json", requested)
        assertNull(reported)
    }

    @Test fun coroutineCancellationIsPreserved() {
        assertThrows(CancellationException::class.java) {
            launchBenchmarkExportDestination("benchmarks.json", { throw CancellationException("leaving") }, { error("Cancellation must not become a picker failure") })
        }
    }

    @Test fun diagnosticRetryRechecksCurrentAuthorityBeforeOpeningTheDestination(): Unit = runBlocking {
        val artifacts = BenchmarkExportArtifactStore(directory = { File(temporary.root, "exports") })
        val artifact = artifacts.prepare(true) { it.write("{\"diagnostic\":true}".toByteArray()) }
        var opened = false
        var debug = false
        val destination = {
            opened = true
            object : OutputStream() {
                override fun write(value: Int) = Unit
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { saveBenchmarkExportArtifact(artifacts, artifact, { debug }, destination) }
        }
        assertFalse(opened)
        assertEquals(artifact, artifacts.find(artifact.id))
        debug = true
        saveBenchmarkExportArtifact(artifacts, artifact, { debug }, destination)
        assertTrue(opened)
        assertNull(artifacts.find(artifact.id))
    }
}
