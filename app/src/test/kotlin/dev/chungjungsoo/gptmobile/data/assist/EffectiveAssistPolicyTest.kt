package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy
import dev.chungjungsoo.gptmobile.data.orchestration.OptimizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveAssistPolicyTest {
    @Test fun migrationPreservesExplicitLimitsAndDoesNotAuthorizeNewProfiles() {
        val helper = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LITERT_LM)
        val original = LocalAssistPolicy(profileUid = helper.uid, maxAttempts = 2, taskTimeoutSeconds = 11, loadTimeoutSeconds = 90)
        val migrated = original.migrateProcessingPermissions(listOf(helper))
        assertTrue(migrated.allowsProcessing(helper))
        assertFalse(migrated.allowsProcessing(helper.copy(uid = "new")))
        assertEquals(original.maxAttempts, migrated.maxAttempts)
        assertEquals(original.loadTimeoutSeconds, migrated.loadTimeoutSeconds)
        assertEquals(AssistPreparation.WARM_ONLY, migrated.preparation)
        val excluded = helper.copy(model = "legacy:free")
        val preserved = original.migrateProcessingPermissions(listOf(excluded))
        assertEquals(AssistProcessingPermission.REVIEW_REQUIRED, preserved.processingPermission(helper))
        assertFalse(preserved.migrateProcessingPermissions(listOf(helper)).allowsProcessing(helper))
        val denied = migrated.copy(profilePermissions = mapOf(helper.uid to AssistProcessingPermission.DENIED))
        assertEquals(denied, denied.migrateProcessingPermissions(listOf(helper)))
    }

    @Test fun executionModesOnlyNarrowTheRequestedPolicy() {
        for (mode in OptimizationMode.entries) {
            val settings = AppFeatureSettings(localAssist = true, localAssistPolicy = LocalAssistPolicy(maxAttempts = 2, summarizeRetrievedText = false, preparation = AssistPreparation.WARM_ONLY), executionPolicy = ExecutionPolicy(mode = mode, coldLoadAssist = true, assistCompression = true))
            val resolved = settings.resolveAssistPolicy()
            assertTrue(resolved.enabled)
            assertFalse(resolved.effective.summarizeRetrievedText)
            assertEquals(AssistPreparation.WARM_ONLY, resolved.effective.preparation)
            assertTrue(resolved.effective.maxAttempts <= resolved.requested.maxAttempts)
        }
    }

    @Test fun explicitPermissionIsIndependentOfMemorySettingsAndProfileStillMustBeEnabled() {
        val helper = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LITERT_LM)
        val policy = LocalAssistPolicy(permissionVersion = 1, profilePermissions = mapOf(helper.uid to AssistProcessingPermission.ALLOWED))
        assertTrue(policy.allowsProcessing(helper))
        assertFalse(policy.allowsProcessing(helper.copy(enabled = false)))
        assertFalse(policy.allowsProcessing(helper.copy(compatibleType = ClientType.OPENAI)))
    }
}
