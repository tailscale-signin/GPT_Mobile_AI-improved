package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.agent.GitHubToolInventory
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayInventoryTool
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayToolInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubInventoryPresentationTest {
    @Test fun searchIncludesActionsAndFeatureTermsWithoutConvertingCatalogEntriesIntoGrants() {
        val catalog = builtInGitHubInventory()
        val ticketmaster = catalog.single { it.key == "tool/places_ticketmaster__events" }
        assertTrue(ticketmaster.details.any { it.contains("Ticketmaster Events") })
        assertTrue(ticketmaster.details.any { it.startsWith("Inputs · ") && "query" in it && "location" in it })
        assertEquals(catalog.size, catalog.map { it.key }.distinct().size)
        assertTrue(gitHubInventoryResults(catalog, GitHubInventorySection.GITHUB, "pull agent_merge_status", null).any { it.title == "Pull requests" })
        assertTrue(gitHubInventoryResults(catalog, GitHubInventorySection.BUILT_IN, "spoken vocabulary", null).any { it.key == "feature/voice-pronunciation-vocabulary" })
        assertTrue(gitHubInventoryResults(catalog, GitHubInventorySection.APP_TOOLS, "read file slice", null).any { it.subtitle.contains("read_file_slice") })
        assertTrue(gitHubInventoryResults(catalog, GitHubInventorySection.RUN, "", null).isEmpty())
        assertTrue(gitHubInventoryResults(catalog, GitHubInventorySection.GATEWAY, "", null).isEmpty())
    }

    @Test fun repeatedGatewayNamesPreserveDistinctSourceDecisionsAndLegacyDiscoveryRemainsUnverified() {
        val allowed = GatewayInventoryTool("read", "Read files", listOf("path"), id = "remote-a", sourceId = "gateway-a", authorized = true, executableNow = true)
        val blocked = allowed.copy(id = "remote-b", sourceId = "gateway-b", authorized = false, executableNow = false, blockedReason = "Scope revoked")
        val snapshot = GatewayToolInventory(listOf(allowed, blocked), 2, 44, scope = "gateway")
        val rows = gatewayInventoryRows(snapshot)
        assertEquals(2, rows.map { it.key }.distinct().size)
        assertEquals(1, gitHubInventoryResults(rows, GitHubInventorySection.GATEWAY, "gateway-b revoked", "Gateway MCP").size)
        assertTrue(rows.first().subtitle.contains("Reported ready at observation"))
        assertTrue(rows.last().subtitle.contains("Access denied at observation"))
        assertFalse(rows.last().details.any { "Granted" in it })
        val legacy = gatewayInventoryRows(snapshot.copy(tools = listOf(allowed.copy(authorized = null, executableNow = null)), scope = "legacy_gateway_mcp"))
        assertTrue(legacy.single().subtitle.contains("Readiness unverified"))
    }

    @Test fun latestRunRequiresTheCurrentChatProfileBranchAndRunEvenWhenAnOlderSnapshotExists() {
        val context = GitHubConversationContext("account", "owner", "repo", "work", "a".repeat(40), "profile")
        val run = AgentRun("inventory-scope-test", 7, 31, 33, "profile", "CUSTOM", "model")
        val observed = GitHubToolInventory.Observation(run.runId, 7, 44, listOf(GitHubToolInventory.Tool("github_read__account", "github_read", "", listOf("lines"))), "account", "owner", "repo", "work", "profile")
        fun latest(chat: Int = 7, scope: GitHubConversationContext = context, runs: List<AgentRun> = listOf(run)) = latestGitHubAdmission(chat, scope, runs, listOf(observed))
        assertEquals(observed, latest())
        assertNull(latest(chat = 8))
        assertNull(latest(scope = context.copy(ref = "main")))
        assertNull(latest(scope = context.copy(connectionUid = "other")))
        assertNull(latest(scope = context.copy(mainProfileUid = "other")))
        assertNull(latest(runs = listOf(run, run.copy(userMessageId = 40, profileUid = "other", runId = "new"))))
        assertNull(latest(runs = listOf(run, run.copy(userMessageId = 40, runId = "new"))))
        val mounted = mountedGitHubInventory(observed)
        assertEquals(1, gitHubInventoryResults(mounted, GitHubInventorySection.RUN, "github_read__account lines", null).size)
        assertTrue(gitHubInventoryResults(mounted, GitHubInventorySection.RUN, "write", null).isEmpty())
    }
}
