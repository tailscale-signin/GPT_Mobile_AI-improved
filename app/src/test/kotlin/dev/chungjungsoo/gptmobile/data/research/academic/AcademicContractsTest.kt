package dev.chungjungsoo.gptmobile.data.research.academic

import android.app.Application
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.academicUnlockReason
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AcademicContractsTest {
    @Test fun oldSettingsAndSelectionsNeverGrantAcademicAccess() {
        val settings = Json.decodeFromString<AppFeatureSettings>("{}")
        assertFalse(settings.isToolPluginEnabled(ToolPluginId.DEEP_RESEARCH))
        assertFalse(MultiSearchResearchSelection().academicEligibleByUi)
        assertNotNull(academicUnlockReason(MultiSearchResearchSelection(true, 80), settings, "profile", true))
        assertNotNull(academicUnlockReason(MultiSearchResearchSelection(true, 80, ResearchContentScope.WEB_ONLY), settings, "profile", true))
        assertFalse(MultiSearchResearchSelection(true, 0).academicEligibleByUi)
    }

    @Test fun perChatDepthDoesNotChangeGlobalDefaultsOrExceedCustomCaps() {
        val defaults = ModelDelegationSettings()
        val before = Json.encodeToString(defaults)
        val quick = MultiSearchResearchSelection(true, 20).applyTo(defaults)
        assertEquals(24, quick.deepResearch.maxPages)
        assertEquals(2, quick.deepResearch.maxRounds)
        assertEquals(before, Json.encodeToString(defaults))
        assertEquals(300, MultiSearchResearchSelection(true, 100).applyTo(defaults).deepResearch.maxPages)
        assertEquals(300, MultiSearchResearchSelection(true, 100).applyTo(defaults.copy(deepResearch = defaults.deepResearch.copy(sourceSafetyCap = 150))).deepResearch.maxPages)
    }

    @Test fun duplicateDoiReceiptsMergeButPreprintVersionsDoNot() {
        val a = AcademicPaper("doi:10.1234/a", "Paper", "https://doi.org/10.1234/a", sourceProviders = listOf("crossref"), providerReceipts = listOf("a"))
        val b = a.copy(sourceProviders = listOf("openalex"), providerReceipts = listOf("b"), isRetracted = true)
        val v1 = AcademicPaper("arxiv:2401.00001v1", "Paper", "https://arxiv.org/abs/2401.00001v1")
        val v2 = v1.copy(paperId = "arxiv:2401.00001v2", versionId = "arxiv:2401.00001v2")
        val merged = mergeAcademicPapers(listOf(a, b, v1, v2))
        assertEquals(3, merged.size)
        assertEquals(listOf("a", "b"), merged.first().providerReceipts)
        assertEquals(true, merged.first().isRetracted)
    }

    @Test fun exactQuoteRequiresCapturedVersionAndIntactSnapshot() {
        val text = "A measured passage contains evidence."
        val evidence = AcademicEvidence("doi:x", "v1", "https://arxiv.org/html/a", 1, researchHash(text), text, "partially_read")
        assertEquals("quote_verified", evidence.verify("measured passage", "v1"))
        assertEquals("version_mismatch", evidence.verify("measured passage", "v2"))
        assertEquals("quote_not_found", evidence.verify("a different result", "v1"))
        assertEquals("not_read", evidence.copy(retrievalStatus = "metadata_only").verify("measured passage", "v1"))
        assertEquals("snapshot_integrity_failed", evidence.copy(text = "changed").verify("changed", "v1"))
    }

    @Test fun liveContractFixturesRemainMetadataAndNeverFabricatePmids() {
        val client = AcademicNativeClient(RuntimeEnvironment.getApplication())
        AcademicNativeClient.CORE.forEach { provider ->
            val extension = if (provider == "arxiv") "xml" else "json"
            val body = javaClass.getResource("/academic/$provider.$extension")!!.readText()
            val papers = client.parseSearch(provider, body)
            assertTrue("$provider fixture", papers.isNotEmpty())
            assertTrue(papers.all { it.title.isNotBlank() && it.sourceAccess == "metadata_only" })
        }
        val preprint = client.parseSearch("europepmc", """{"resultList":{"result":[{"id":"PPR1","source":"PPR","title":"Preprint"}]}}""").single()
        assertFalse("pmid" in preprint.identifiers)
    }

    @Test fun revokedPermissionStopsBeforeAnyHttpRequest() = runTest {
        val client = AcademicNativeClient(RuntimeEnvironment.getApplication())
        val result = client.search(AcademicSearchRequest("mobile inference", 80), MultiSearchResearchSelection(true, 80)) { false }
        assertTrue(result.papers.isEmpty())
        assertTrue(result.providers.all { it.state == "permission_denied" })
    }
}
