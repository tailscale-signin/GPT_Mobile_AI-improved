package dev.chungjungsoo.gptmobile.data.research.work

import dev.chungjungsoo.gptmobile.data.accounting.ModelPrice
import dev.chungjungsoo.gptmobile.data.accounting.SpendBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExaminationConfigurationRevisionTest {
    private val profile = PlatformV2(uid = "crawler", name = "Crawler", enabled = true)
    private val price = ModelPrice("model", 10, 20, "catalog", 1)
    private val features = AppFeatureSettings(spendBudget = SpendBudgetSettings(prices = mapOf("crawler" to price)))

    @Test fun displayEditsAndQuoteRefreshDoNotWakeBlockedJobs() {
        val changed = features.copy(debugShowRuntime = !features.debugShowRuntime, researchFeature = features.researchFeature.copy(compactCards = false, showWarnings = false), spendBudget = features.spendBudget.copy(prices = mapOf("crawler" to price.copy(checkedAt = 2, source = "refresh"))))
        assertEquals(examinationConfigurationRevision(features, listOf(profile)), examinationConfigurationRevision(changed, listOf(profile.copy(name = "Renamed", isFavorite = true))))
    }

    @Test fun examinerChoicePermissionsAndCredentialsWakeBlockedJobs() {
        val initial = examinationConfigurationRevision(features, listOf(profile))
        assertNotEquals(initial, examinationConfigurationRevision(features.copy(conversationResearch = mapOf(4 to MultiSearchResearchSelection(crawlerProfileUid = "crawler"))), listOf(profile)))
        assertNotEquals(initial, examinationConfigurationRevision(features, listOf(profile.copy(disableRemoteTools = true))))
        assertNotEquals(initial, examinationConfigurationRevision(features, listOf(profile.copy(token = "changed"))))
        assertNotEquals(initial, examinationConfigurationRevision(features.copy(remoteMcpConnections = !features.remoteMcpConnections), listOf(profile)))
    }
}
