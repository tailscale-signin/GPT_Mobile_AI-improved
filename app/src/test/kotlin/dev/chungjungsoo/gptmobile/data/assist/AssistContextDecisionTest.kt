package dev.chungjungsoo.gptmobile.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistContextDecisionTest {
    @Test fun exactNonPowerOfTwoCapacityAndAllocationSurvive() {
        val exact = LocalAssistPolicy(contextTokens = 1280)
        assertEquals(1280, exact.validated().contextTokens)
        assertEquals(1280, AssistContextDecision.resolve(exact, 1280, false).allocatedTokens)
        val fixed = AssistContextDecision.resolve(LocalAssistPolicy(contextTokens = 4096), 4224, false)
        assertEquals(4224, fixed.supportedTokens)
        assertEquals(4224, fixed.allocatedTokens)
        assertEquals(4096, fixed.logicalTokens)
        assertNull(fixed.qualifiedTokens)
    }

    @Test fun unsupportedManualContextRejectsWithoutEditingIntent() {
        val saved = LocalAssistPolicy(contextTokens = 8192)
        assertTrue(runCatching { AssistContextDecision.resolve(saved, 4096, false) }.isFailure)
        assertEquals(8192, saved.validated().contextTokens)
        assertTrue(runCatching { AssistContextDecision.resolve(saved, 32768, true) }.isFailure)
        assertTrue(runCatching { AssistContextDecision.resolve(saved, null, true) }.isFailure)
    }

    @Test fun dynamicAutoAllocationAndAllReservesAreAccountedFor() {
        val plan = AssistContextDecision.resolve(LocalAssistPolicy(contextMode = AssistContextMode.AUTO), 8192, true)
        assertNull(plan.requestedTokens)
        assertEquals(4096, plan.allocatedTokens)
        assertEquals(4096L, plan.requireFits(2048, 1024, 512, 256, 256))
        assertTrue(runCatching { plan.requireFits(2049, 1024, 512, 256, 256) }.isFailure)
        assertTrue(runCatching { plan.requireFits(1, -1, 0) }.isFailure)
        assertTrue(runCatching { plan.requireFits(Long.MAX_VALUE, 1024, 512) }.isFailure)
    }

    @Test fun v3CannotGrantNewScopesAndRestoreIsIdempotent() {
        val old = """{"localAssist":false,"localAssistPolicy":{"version":3,"profileUid":"stable","contextTokens":1280,"preloadInForeground":false,"imageProcessing":true,"autonomousReads":true,"sharePrivateQueries":true,"shareDerivedEvidence":true,"profilePermissions":{"stable":"DENIED"}}}"""
        val once = LocalAssistSettingsMigration.normalize(old)
        assertEquals(once, LocalAssistSettingsMigration.normalize(once))
        val settings = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings>(once)
        assertFalse(settings.localAssist)
        assertEquals(4, settings.localAssistPolicy.version)
        assertEquals(1280, settings.localAssistPolicy.contextTokens)
        assertFalse(settings.localAssistPolicy.preloadInForeground)
        assertFalse(settings.localAssistPolicy.imageProcessing)
        assertFalse(settings.localAssistPolicy.autonomousReads)
        assertFalse(settings.localAssistPolicy.sharePrivateQueries)
        assertFalse(settings.localAssistPolicy.shareDerivedEvidence)
        assertEquals(AssistProcessingPermission.DENIED, settings.localAssistPolicy.profilePermissions["stable"])
    }
}
