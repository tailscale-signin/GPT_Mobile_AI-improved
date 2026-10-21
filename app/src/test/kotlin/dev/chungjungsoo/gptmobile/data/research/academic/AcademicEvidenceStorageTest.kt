package dev.chungjungsoo.gptmobile.data.research.academic

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcademicEvidenceStorageTest {
    @Test fun durableEvidenceUsesBoundedBodySidecarAndRetainsLegacyInlineCompatibility() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("academic-bodies").toFile()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
        }
        try {
            val paper = AcademicPaper("doi:10.1234/example", "Study", "https://example.org/article", abstract = "Measured outcomes in 24 adults did not differ. ".repeat(20))
            val evidence = AcademicNativeClient(context).read(paper, { true }, persistent = true)
            val storage = File(directory, "academic-evidence-v1")
            val record = File(storage, researchHash("${evidence.paperId}|${evidence.versionId}") + ".json")
            val saved = Json.decodeFromString<AcademicEvidence>(record.readText())
            assertEquals("", saved.text)
            assertNotNull(saved.bodyReference)
            assertTrue(File(storage, saved.bodyReference!!).isFile)
            assertFalse(record.readText().contains(paper.abstract))
            val loaded = AcademicNativeClient(context).evidence(evidence.paperId, evidence.versionId)
            assertEquals(paper.abstract, loaded!!.text)
            assertEquals(evidence.textHash, loaded.textHash)
            val legacy = evidence.copy(paperId = "legacy", versionId = "v1", bodyReference = null)
            File(storage, researchHash("legacy|v1") + ".json").writeText(Json.encodeToString(legacy))
            assertEquals(legacy, AcademicNativeClient(context).evidence("legacy", "v1"))
            File(storage, saved.bodyReference!!).writeText("Corrupted source body")
            assertNull(AcademicNativeClient(context).evidence(evidence.paperId, evidence.versionId))
            val traversal = legacy.copy(paperId = "unsafe", bodyReference = "../private")
            File(storage, researchHash("unsafe|v1") + ".json").writeText(Json.encodeToString(traversal))
            assertNull(AcademicNativeClient(context).evidence("unsafe", "v1"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun temporaryAcademicReadingDoesNotWriteEvidenceBodies() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("academic-temporary").toFile()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
        }
        try {
            val client = AcademicNativeClient(context)
            val paper = AcademicPaper("paper", "Study", "https://example.org/article", abstract = "A short meaningful provider abstract.")
            val evidence = client.read(paper, { true }, persistent = false)
            assertEquals(evidence, client.evidence(evidence.paperId, evidence.versionId))
            assertFalse(File(directory, "academic-evidence-v1").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
