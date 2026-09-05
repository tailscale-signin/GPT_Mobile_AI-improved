package dev.chungjungsoo.gptmobile.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPresetCatalogTest {

    @Test
    fun presetsListIsNotEmpty() {
        assertTrue(McpPresetCatalog.presets.isNotEmpty())
    }

    @Test
    fun allPresetIdsAreUnique() {
        val ids = McpPresetCatalog.presets.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun allPresetAliasesAreUnique() {
        val aliases = McpPresetCatalog.presets.map { it.alias }
        assertEquals(aliases.size, aliases.distinct().size)
    }

    @Test
    fun findByAliasReturnsCorrectPreset() {
        val github = McpPresetCatalog.findByAlias("github")
        assertNotNull(github)
        assertEquals("GitHub", github?.name)
        assertEquals(McpPresetCategory.DEVELOPMENT, github?.category)

        val nonExistent = McpPresetCatalog.findByAlias("non_existent_preset")
        assertNull(nonExistent)
    }

    @Test
    fun findByIdReturnsCorrectPreset() {
        val fetch = McpPresetCatalog.findById("fetch")
        assertNotNull(fetch)
        assertEquals("Fetch", fetch?.name)
        assertEquals(McpPresetCategory.BROWSER, fetch?.category)
    }

    @Test
    fun getByCategoryFiltersAccurately() {
        val searchPresets = McpPresetCatalog.getByCategory(McpPresetCategory.SEARCH)
        assertTrue(searchPresets.isNotEmpty())
        assertTrue(searchPresets.all { it.category == McpPresetCategory.SEARCH })

        val devPresets = McpPresetCatalog.getByCategory(McpPresetCategory.DEVELOPMENT)
        assertTrue(devPresets.isNotEmpty())
        assertTrue(devPresets.all { it.category == McpPresetCategory.DEVELOPMENT })
    }

    @Test
    fun presetsContainExpectedMajorServers() {
        val aliases = McpPresetCatalog.presets.map { it.alias }.toSet()
        assertTrue(aliases.contains("brave_search"))
        assertTrue(aliases.contains("github"))
        assertTrue(aliases.contains("filesystem"))
        assertTrue(aliases.contains("fetch"))
        assertTrue(aliases.contains("memory"))
    }
}
