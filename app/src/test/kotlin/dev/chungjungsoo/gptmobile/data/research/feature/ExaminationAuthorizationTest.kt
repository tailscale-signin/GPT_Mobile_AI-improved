package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationAuthorizationTest {
    private val profile = PlatformV2(uid = "examiner", name = "Examiner", model = "model")
    private val features = AppFeatureSettings(delegation = ModelDelegationSettings(deepResearch = DeepResearchSettings(academicSources = true)))
    private val tools = ChatMcpToolConfig()

    @Test fun reportPresentationAndCapacityChangesDoNotRevokeArticlePermissions() {
        val before = examinationAuthorization(features, profile, tools, 1)
        val changed = features.copy(delegation = features.delegation.copy(deepResearch = features.delegation.deepResearch.copy(maxPages = 99, maxRounds = 7)))
        val after = examinationAuthorization(changed, profile.copy(name = "Renamed", maxTokens = 2048), tools.copy(reasoning = true, maxTools = 5), 1)
        assertNull(examinationAuthorizationChange(before, after))
    }

    @Test fun revocationAndDestinationChangesIdentifyTheSpecificPermission() {
        val before = examinationAuthorization(features, profile, tools, 1)
        assertEquals("papers_consent", examinationAuthorizationChange(before, examinationAuthorization(features.copy(delegation = features.delegation.copy(deepResearch = features.delegation.deepResearch.copy(academicSources = false))), profile, tools, 1)))
        assertEquals("destination", examinationAuthorizationChange(before, examinationAuthorization(features, profile.copy(apiUrl = "https://other.example"), tools, 1)))
        assertEquals("tool_grants", examinationAuthorizationChange(before, examinationAuthorization(features, profile, tools.withToolDisabled("read_url"), 1)))
    }

    @Test fun deterministicFailuresWaitForAnExplicitNewAttemptOrChangedConfiguration() {
        assertTrue(blocksUnchangedExaminationRetry(ExaminationFailure("OUTPUT_INVALID", ExaminationStage.FAILED, false)))
        assertTrue(blocksUnchangedExaminationRetry(ExaminationFailure("NO_GROUNDED_OUTCOME", ExaminationStage.FAILED, false)))
        assertFalse(blocksUnchangedExaminationRetry(ExaminationFailure("TIMEOUT", ExaminationStage.WAITING_NETWORK, true)))
        assertFalse(blocksUnchangedExaminationRetry(null))
    }
}
