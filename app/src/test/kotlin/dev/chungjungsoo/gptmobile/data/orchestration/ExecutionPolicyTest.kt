package dev.chungjungsoo.gptmobile.data.orchestration

import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistSettingsMigration
import dev.chungjungsoo.gptmobile.data.assist.advanceAssistEpoch
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPolicyTest {
    @Test fun everyRetiredModePreservesExplicitAssistAndIndependentBudgets() {
        for (mode in OptimizationMode.entries) {
            val source = """{"executionPolicy":{"mode":"${mode.name}","sharedRunBudget":true,"physicalModelRequests":1},"localAssist":false,"localAssistPolicy":{"profileUid":"chosen","preloadInForeground":false,"profilePermissions":{"chosen":"DENIED"}},"tokenBudget":{"totalRunTokens":12345},"spendBudget":{"perTurnMicros":987}}"""
            val normalized = LocalAssistSettingsMigration.normalize(source)
            assertFalse(Json.parseToJsonElement(normalized).jsonObject.containsKey("executionPolicy"))
            assertEquals(normalized, LocalAssistSettingsMigration.normalize(normalized))
            val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<AppFeatureSettings>(normalized)
            assertFalse(decoded.localAssist)
            assertEquals("chosen", decoded.localAssistPolicy.profileUid)
            assertFalse(decoded.localAssistPolicy.preloadInForeground)
            assertEquals(12345, decoded.tokenBudget.totalRunTokens)
            assertEquals(987L, decoded.spendBudget.perTurnMicros)
        }
    }

    @Test fun retiredPolicyCannotChangeEffectiveAssist() {
        val base = AppFeatureSettings(localAssist = true, localAssistPolicy = LocalAssistPolicy(maxAttempts = 3))
        for (mode in OptimizationMode.entries) assertEquals(base.effectiveAssistPolicy(), base.copy(executionPolicy = ExecutionPolicy(mode = mode)).effectiveAssistPolicy())
        assertFalse(base.copy(localAssist = false).assistEnabled())
    }

    @Test fun malformedAndFutureSettingsArePreservedByFailingBeforeMutation() {
        assertTrue(runCatching { LocalAssistSettingsMigration.normalize("{broken") }.isFailure)
        assertTrue(runCatching { LocalAssistSettingsMigration.normalize("""{"assistConfigVersion":5}""") }.isFailure)
        for (malformed in listOf("""{"assistConfigVersion":"future"}""", """{"localAssistPolicy":"broken"}""", """{"localAssistPolicy":null}""", """{"localAssistPolicy":{"version":-1}}""", """{"localAssistPolicy":{"version":"4"}}""")) {
            assertTrue(runCatching { LocalAssistSettingsMigration.normalize(malformed) }.isFailure)
        }
    }

    @Test fun revocationAndRegrantAdvanceConsentIdentity() {
        val initial = AppFeatureSettings(localAssist = true)
        val revoked = initial.copy(localAssist = false).advanceAssistEpoch(initial)
        val regranted = revoked.copy(localAssist = true).advanceAssistEpoch(revoked)
        assertFalse(revoked.localAssistPolicy.permissionEpoch == initial.localAssistPolicy.permissionEpoch)
        assertFalse(regranted.localAssistPolicy.permissionEpoch == revoked.localAssistPolicy.permissionEpoch)
        assertEquals(regranted, regranted.advanceAssistEpoch(regranted))
    }
}
