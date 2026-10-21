package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationPolicyResolverTest {
    @Test fun narrowerScopesNeverEnableAMasterOrGrantCloudPermission() {
        val features = AppFeatureSettings(delegation = ModelDelegationSettings(enabled = false))
        val chat = ConversationDelegationSettings(true, allowRemoteWorker = true, cloudPermission = ScopedPermission.ALLOW)
        assertFalse(DelegationPolicyResolver.resolve(features, "primary", chat).enabled)
        assertFalse(DelegationPolicyResolver.resolve(features, "primary", chat).remoteWorkersAllowed())
        val global = features.copy(delegation = features.delegation.copy(enabled = true).withRemoteWorkersAllowed(true))
        assertFalse(DelegationPolicyResolver.resolve(global, "primary", chat).enabled)
        val participating = global.copy(profileBehavior = mapOf("primary" to ProfileBehaviorSettings(delegationEnabled = true)))
        assertTrue(DelegationPolicyResolver.resolve(participating, "primary", chat).enabled)
        assertFalse(DelegationPolicyResolver.resolve(participating, "primary", chat.copy(cloudPermission = ScopedPermission.DENY)).remoteWorkersAllowed())
    }

    @Test fun missingFixedTargetNeverBecomesAutomatic() {
        val defaults = ModelDelegationSettings(enabled = true).normalized()
        val fixed = ConversationDelegationSettings(true, selectionMode = WorkerSelectionMode.FIXED)
        assertFalse(fixed.applyTo(defaults).enabled)
    }

    @Test fun migrationPreservesLegacyEffectiveCapsThenExplicitCapsWin() {
        val old = ModelDelegationSettings(processingOwnership = 0, maxLocalModelCalls = 2, allowRemoteWorkers = true)
        val migrated = old.normalized()
        assertEquals(old.effectiveLocalModelCalls(), migrated.effectiveLocalModelCalls())
        assertTrue(migrated.remoteWorkersAllowed())
        assertEquals(2, migrated.copy(maxLocalModelCalls = 2).withDelegationAmount(100).effectiveLocalModelCalls())
    }

    @Test fun approvedOrderAndModelIndependenceUseIdentity() {
        val first = PlatformV2(uid = "a", name = "Same label", model = "model-a", apiUrl = "http://localhost:8080")
        val second = first.copy(uid = "b", model = "model-b")
        val excluded = first.copy(uid = "c", model = "model-c")
        val policy = ModelDelegationSettings(approvedWorkerUids = listOf("b", "a"))
        assertEquals(listOf("b", "a"), DelegationPolicyResolver.ranked(listOf(first, second, excluded), policy).map { it.uid })
        assertFalse(DelegationPolicyResolver.sameModel(first, second))
        assertTrue(DelegationPolicyResolver.sameModel(first, first.copy(uid = "alias")))
        assertEquals(ModelIndependence.UNKNOWN, CanonicalModelIdentity.independence(first, first.copy(uid = "alias")))
        assertFalse(DelegationPolicyResolver.sameModel(first, first.copy(uid = "other-deployment", apiUrl = "http://localhost:9090")))
    }

    @Test fun importedTemplateIsStrictAndCannotCarryPermissions() {
        val template = DelegationTemplate(role = DelegationTemplate.Role.RESEARCH)
        val original = ModelDelegationSettings(enabled = false, targetProfileUid = "fixed", cloudPermission = ScopedPermission.DENY)
        val applied = DelegationTemplate.decode(DelegationTemplate.encode(template)).applyTo(original)
        assertFalse(applied.enabled)
        assertFalse(applied.remoteWorkersAllowed())
        assertEquals("fixed", applied.targetProfileUid)
        assertTrue(runCatching { DelegationTemplate.decode("""{"schema":1,"role":"RESEARCH","allowRemoteWorkers":true}""") }.isFailure)
    }
}
