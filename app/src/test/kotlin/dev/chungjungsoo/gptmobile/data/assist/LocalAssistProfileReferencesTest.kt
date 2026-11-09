package dev.chungjungsoo.gptmobile.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LocalAssistProfileReferencesTest {
    @Test fun missingProfileIsClearedWithoutSelectingAnotherAvailableModel() {
        val saved = LocalAssistPolicy(
            profileUid = "removed-profile",
            permissionEpoch = "old-epoch",
            profilePermissions = mapOf("removed-profile" to AssistProcessingPermission.ALLOWED, "other" to AssistProcessingPermission.DENIED)
        )
        val repaired = saved.reconcileSelectedProfile(setOf("other", "new-local-profile"))
        assertNull(repaired.profileUid)
        assertNotEquals("old-epoch", repaired.permissionEpoch)
        assertEquals(mapOf("other" to AssistProcessingPermission.DENIED), repaired.profilePermissions)
        assertSame(repaired, repaired.reconcileSelectedProfile(setOf("other")))
    }

    @Test fun explicitExistingSelectionAndUnconfiguredStateRemainUnchanged() {
        val saved = LocalAssistPolicy(profileUid = "chosen", permissionEpoch = "confirmed")
        assertSame(saved, saved.reconcileSelectedProfile(setOf("chosen", "other")))
        val unconfigured = LocalAssistPolicy()
        assertSame(unconfigured, unconfigured.reconcileSelectedProfile(setOf("other")))
    }
}
