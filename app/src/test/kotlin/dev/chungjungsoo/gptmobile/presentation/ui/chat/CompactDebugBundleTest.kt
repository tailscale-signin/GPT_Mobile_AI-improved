package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactDebugBundleTest {
    @Test fun streamsRecentLogsAndLabelsOmittedEvidence() {
        val directory = Files.createTempDirectory("compact-export").toFile()
        try {
            var consumed = 0
            val file = CompactDebugBundle.write(directory, 22, sequence {
                repeat(2000) { consumed++; yield("redacted record $it") }
            })
            assertEquals(2000, consumed)
            ZipFile(file).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("\"capturedFully\":false"))
                assertTrue(manifest.contains("tool_receipts"))
                val lines = zip.getInputStream(zip.getEntry("recent-app-logs.txt")).bufferedReader().use { it.readLines() }
                assertEquals(2000, lines.size)
                assertEquals("redacted record 1999", lines.last())
            }
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "partial" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun failedExportPublishesNothingAndCleansStaging() {
        val directory = Files.createTempDirectory("compact-export-failure").toFile()
        try {
            val failure = runCatching { CompactDebugBundle.write(directory, 22, sequence {
                yield("first")
                error("storage unavailable")
            }) }.exceptionOrNull()
            assertEquals("storage unavailable", failure?.message)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
