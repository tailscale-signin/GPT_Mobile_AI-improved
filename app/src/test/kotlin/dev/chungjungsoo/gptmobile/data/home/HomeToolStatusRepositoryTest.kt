package dev.chungjungsoo.gptmobile.data.home

import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealth
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealthState
import dev.chungjungsoo.gptmobile.data.model.HomeToolSubject
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolHealthObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeToolStatusRepositoryTest {
    @Test fun savedConfigurationAloneDoesNotProveAvailability() {
        assertEquals("Not checked", HomeToolStatusRepository.connectionStatus(true, 1, 100, null, null).label)
        assertEquals("Disabled", HomeToolStatusRepository.connectionStatus(false, 1, 100, McpConnectionHealth(McpConnectionHealthState.CONNECTED, lastSuccessAtMs = 90), null).label)
    }

    @Test fun changedConnectionsAndExpiredObservationsCannotRemainGreen() {
        val live = McpConnectionHealth(McpConnectionHealthState.CONNECTED, lastSuccessAtMs = 100)
        val saved = ToolHealthObservation("connection", "CONNECTED", "MCP", 100, 300_100)
        assertEquals(SettingsHomeTone.POSITIVE, HomeToolStatusRepository.connectionStatus(true, 1, 200, live, saved).tone)
        assertEquals(SettingsHomeTone.STALE, HomeToolStatusRepository.connectionStatus(true, 201, 202, live, saved).tone)
        assertEquals(SettingsHomeTone.STALE, HomeToolStatusRepository.connectionStatus(true, 1, 300_101, live, saved).tone)
        assertEquals(SettingsHomeTone.STALE, HomeToolStatusRepository.connectionStatus(true, 1, 99, live, saved).tone)
    }

    @Test fun newerPersistedFailureOverridesOlderLiveSuccess() {
        val live = McpConnectionHealth(McpConnectionHealthState.CONNECTED, lastSuccessAtMs = 100)
        val saved = ToolHealthObservation("connection", "AUTHENTICATION_REQUIRED", "MCP", 200, 300_200)
        assertEquals("Authentication required", HomeToolStatusRepository.connectionStatus(true, 1, 300, live, saved).label)
    }

    @Test fun individualOutcomeCannotChangeItsParentOrSiblingHealth() {
        val parent = HomeToolSubject("connection")
        val success = HomeToolStatus("Last request succeeded", SettingsHomeTone.POSITIVE)
        val failure = HomeToolStatus("Last request failed", SettingsHomeTone.NEGATIVE)
        val group = HomeToolGroup(parent, "Reader", HomeToolStatus("Online", SettingsHomeTone.POSITIVE), listOf("read", "search"), true, mapOf("read" to failure, "search" to success), failure)
        assertEquals("Online", homeToolSubjectStatus(parent, group).label)
        assertEquals("Last request failed", homeToolSubjectStatus(parent.copy(toolName = "read"), group).label)
        assertEquals("Last request succeeded", homeToolSubjectStatus(parent.copy(toolName = "search"), group).label)
        assertEquals("Disabled", homeToolSubjectStatus(parent.copy(toolName = "search"), group.copy(status = HomeToolStatus("Disabled", SettingsHomeTone.NEGATIVE))).label)
    }

    @Test fun staleAndFutureToolResultsNeverRemainGreen() {
        val call = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolInvocation("call", "subject", "epoch", "profile", "root", null, "owner", 10, 20, "SUCCESS")
        assertEquals(SettingsHomeTone.POSITIVE, HomeToolStatusRepository.requestStatus(call, 21).tone)
        assertEquals(SettingsHomeTone.STALE, HomeToolStatusRepository.requestStatus(call, 19).tone)
        assertEquals(SettingsHomeTone.STALE, HomeToolStatusRepository.requestStatus(call, 300021).tone)
    }

    @Test fun missingToolDoesNotMakeItsReachableParentOffline() {
        val parent = HomeToolSubject("connection")
        val group = HomeToolGroup(parent, "Reader", HomeToolStatus("Online", SettingsHomeTone.POSITIVE), listOf("read"), true)
        assertEquals("Online", homeToolSubjectStatus(parent, group).label)
        assertEquals("Tool unavailable", homeToolSubjectStatus(parent.copy(toolName = "removed"), group).label)
        assertEquals("Unavailable", homeToolSubjectStatus(parent, null).label)
    }
}
