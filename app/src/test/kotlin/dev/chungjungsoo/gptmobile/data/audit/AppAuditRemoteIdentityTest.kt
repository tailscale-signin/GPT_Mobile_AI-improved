package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppAuditRemoteIdentityTest {
    @Test
    fun unknownRemoteFieldsAreDroppedWhileValidDeclarationsRemainBoundToTheirSource() {
        val identity = AppAuditRemoteIdentity(version = "15.0.0", instanceId = "private-instance", sourceCommit = "a".repeat(40), buildStatus = "private-build")
        val receipt = requireNotNull(appAuditRemoteIdentityJson(identity))
        assertEquals("DECLARED_CAPABILITIES", receipt["source"]?.jsonPrimitive?.content)
        assertEquals("15.0.0", receipt["version"]?.jsonPrimitive?.content)
        assertEquals("a".repeat(40), receipt["sourceCommit"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, receipt["instanceId"])
        assertEquals(JsonNull, receipt["buildStatus"])
        assertFalse(receipt.toString().contains("private"))
        assertNull(safeAuditRemoteIdentity(AppAuditRemoteIdentity()))
    }

    @Test
    fun legacyObservationDecodesWithoutInventingRemoteIdentity() {
        val observation = Json.decodeFromString<AppAuditObservation>("""{"outcome":"PASS","reasonCode":"GATEWAY_CAPABILITY_VERIFIED"}""")
        assertNull(observation.remoteIdentity)
        assertNull(observation.configurationRevision)
        assertNull(observation.observedAtEpochMs)
    }
}
