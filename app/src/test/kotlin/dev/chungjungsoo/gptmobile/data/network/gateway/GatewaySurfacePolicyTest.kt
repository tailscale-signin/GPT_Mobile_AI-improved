package dev.chungjungsoo.gptmobile.data.network.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySurfacePolicyTest {
    @Test fun onlyDefiniteUnsupportedOrRestrictedSurfacesAreRemembered() {
        val policy = GatewaySurfacePolicy({ 0 })
        for (status in listOf(401, 403, 408, 429, 500, 502, 503)) {
            policy.observe("route", GatewaySurfacePolicy.Surface.CAPABILITIES, status)
            assertNull(policy.rememberedStatus("route", GatewaySurfacePolicy.Surface.CAPABILITIES))
        }
        policy.observe("route", GatewaySurfacePolicy.Surface.CAPABILITIES, 404)
        assertEquals(404, policy.rememberedStatus("route", GatewaySurfacePolicy.Surface.CAPABILITIES))
        policy.observe("route", GatewaySurfacePolicy.Surface.PROPS, 403, "scope_not_granted")
        assertNull(policy.rememberedStatus("route", GatewaySurfacePolicy.Surface.PROPS))
        policy.observe("route", GatewaySurfacePolicy.Surface.PROPS, 403, "remote_surface_not_admitted")
        assertEquals(403, policy.rememberedStatus("route", GatewaySurfacePolicy.Surface.PROPS))
        assertNull(policy.rememberedStatus("route", GatewaySurfacePolicy.Surface.HEALTH))
    }

    @Test fun routeRevisionAndExpiryPermitRecoveryWithoutProbingEveryRequest() {
        var now = 10L
        val policy = GatewaySurfacePolicy({ now }, ttlMillis = 100)
        policy.observe("route|credential|network|runtime1", GatewaySurfacePolicy.Surface.CAPABILITIES, 501)
        assertNull(policy.rememberedStatus("route|credential|network|runtime2", GatewaySurfacePolicy.Surface.CAPABILITIES))
        assertNull(policy.rememberedStatus("route|new-credential|network|runtime1", GatewaySurfacePolicy.Surface.CAPABILITIES))
        now = 109
        assertEquals(501, policy.rememberedStatus("route|credential|network|runtime1", GatewaySurfacePolicy.Surface.CAPABILITIES))
        now = 110
        assertNull(policy.rememberedStatus("route|credential|network|runtime1", GatewaySurfacePolicy.Surface.CAPABILITIES))
    }

    @Test fun successfulObservationReplacesAnEarlierRestriction() {
        val policy = GatewaySurfacePolicy({ 0 })
        policy.observe("route", GatewaySurfacePolicy.Surface.PROPS, 403, "remote_surface_not_admitted")
        policy.observe("route", GatewaySurfacePolicy.Surface.PROPS, 200)
        assertNull(policy.rememberedStatus("route", GatewaySurfacePolicy.Surface.PROPS))
    }

    @Test fun explicitReconnectOnlyInvalidatesTheSelectedRoute() {
        val policy = GatewaySurfacePolicy({ 0 })
        policy.observe("selected", GatewaySurfacePolicy.Surface.CAPABILITIES, 404)
        policy.observe("other", GatewaySurfacePolicy.Surface.CAPABILITIES, 404)
        policy.invalidate("selected")
        assertNull(policy.rememberedStatus("selected", GatewaySurfacePolicy.Surface.CAPABILITIES))
        assertEquals(404, policy.rememberedStatus("other", GatewaySurfacePolicy.Surface.CAPABILITIES))
    }

    @Test fun explicitAdministrativeRestrictionSurvivesTimeUntilRouteIdentityOrReconnectChanges() {
        var now = 0L
        val policy = GatewaySurfacePolicy({ now }, ttlMillis = 100)
        val route = "route|credential|network|instance|revision"
        for (surface in listOf(GatewaySurfacePolicy.Surface.PROPS, GatewaySurfacePolicy.Surface.HEALTH)) {
            policy.observe(route, surface, 403, "remote_surface_not_admitted")
            now += 86_400_000L
            assertEquals(403, policy.rememberedStatus(route, surface))
            assertNull(policy.rememberedStatus("$route-new-revision", surface))
            policy.invalidate(route, surface)
            assertNull(policy.rememberedStatus(route, surface))
        }
    }
}
