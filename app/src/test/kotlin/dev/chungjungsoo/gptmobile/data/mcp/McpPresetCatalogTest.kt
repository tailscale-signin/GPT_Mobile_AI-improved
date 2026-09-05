package dev.chungjungsoo.gptmobile.data.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPresetCatalogTest {

    @Test
    fun `catalog should not be empty`() {
        assertTrue("Preset catalog should contain presets", McpPresetCatalog.presets.isNotEmpty())
    }

    @Test
    fun `preset IDs should be unique`() {
        val ids = McpPresetCatalog.presets.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("All preset IDs must be unique", ids.size, uniqueIds.size)
    }

    @Test
    fun `all presets should have non-empty name and description`() {
        for (preset in McpPresetCatalog.presets) {
            assertTrue("Preset ${preset.id} has blank name", preset.name.isNotBlank())
            assertTrue("Preset ${preset.id} has blank description", preset.description.isNotBlank())
            assertTrue("Preset ${preset.id} has blank commandOrUrl", preset.commandOrUrl.isNotBlank())
        }
    }

    @Test
    fun `brave search preset should require BRAVE_API_KEY`() {
        val bravePreset = McpPresetCatalog.presets.find { it.id == "brave-search" }
        assertNotNull("Brave Search preset must exist", bravePreset)
        assertEquals(McpCategory.SEARCH, bravePreset?.category)
        assertTrue(
            "Brave search should require BRAVE_API_KEY",
            bravePreset?.requiredEnvKeys?.contains("BRAVE_API_KEY") == true
        )
    }

    @Test
    fun `github preset should require GITHUB_PERSONAL_ACCESS_TOKEN`() {
        val githubPreset = McpPresetCatalog.presets.find { it.id == "github" }
        assertNotNull("GitHub preset must exist", githubPreset)
        assertEquals(McpCategory.DEVELOPMENT, githubPreset?.category)
        assertTrue(
            "GitHub should require GITHUB_PERSONAL_ACCESS_TOKEN",
            githubPreset?.requiredEnvKeys?.contains("GITHUB_PERSONAL_ACCESS_TOKEN") == true
        )
    }

    @Test
    fun `postgres preset should require connection string`() {
        val postgresPreset = McpPresetCatalog.presets.find { it.id == "postgres" }
        assertNotNull("Postgres preset must exist", postgresPreset)
        assertEquals(McpCategory.DATABASE, postgresPreset?.category)
        assertTrue(
            "Postgres should require POSTGRES_CONNECTION_STRING",
            postgresPreset?.requiredEnvKeys?.contains("POSTGRES_CONNECTION_STRING") == true
        )
    }

    @Test
    fun `all presets should have STDIO transport type`() {
        for (preset in McpPresetCatalog.presets) {
            assertEquals(
                "Preset ${preset.id} should use STDIO transport",
                McpTransportType.STDIO,
                preset.transportType
            )
        }
    }
}
