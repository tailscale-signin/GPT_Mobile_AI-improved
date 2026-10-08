package dev.chungjungsoo.gptmobile.data.catalog

import dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceDownloadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubMarketplaceCatalogTest {
    private val packages = GitHubMarketplaceCatalog.packages

    @Test
    fun catalogHasUniqueOptInEntriesAndValidAliases() {
        assertEquals(19, packages.size)
        assertEquals(packages.size, packages.map { it.id }.toSet().size)
        assertEquals(packages.size, packages.map { it.preset.alias }.toSet().size)
        packages.forEach {
            assertFalse(it.preset.isPreinstalled)
            assertEquals(null, it.preset.integratedTool)
            assertTrue(it.preset.alias.matches(Regex("[a-z][a-z0-9_]{0,31}")))
        }
    }

    @Test
    fun guidesCannotBecomeConnections() {
        assertEquals(11, packages.count { it.runtime == MarketplaceRuntime.NATIVE })
        assertEquals(2, packages.count { it.runtime == MarketplaceRuntime.HOSTED })
        assertEquals(6, packages.count { !it.canConnect })
        packages.filter { !it.canConnect }.forEach {
            assertTrue(it.preset.documentationOnly)
            assertTrue(it.preset.commandOrUrl.isEmpty())
        }
    }

    @Test
    fun nativeAndRemoteSectionsRemainSeparate() {
        val native = packages.first().preset.copy(id = "native", commandOrUrl = "builtin://device_location")
        assertEquals(MarketplaceSection.PLUGINS, MarketplacePresentation.section(native))
        packages.forEach { assertEquals(if (it.runtime == MarketplaceRuntime.NATIVE) MarketplaceSection.PLUGINS else MarketplaceSection.MCP, MarketplacePresentation.section(it.preset)) }
    }

    @Test
    fun sortingAndSearchUseStableMetadata() {
        val presets = packages.map { it.preset }
        val asc = MarketplacePresentation.filterAndSort(
            presets,
            MarketplaceSection.MCP,
            "",
            null,
            null,
            MarketplaceSort.NAME_ASC,
            emptySet()
        )
        val desc = MarketplacePresentation.filterAndSort(
            presets,
            MarketplaceSection.MCP,
            "",
            null,
            null,
            MarketplaceSort.NAME_DESC,
            emptySet()
        )
        assertEquals(asc.reversed(), desc)
        val result = MarketplacePresentation.filterAndSort(
            presets,
            MarketplaceSection.PLUGINS,
            " EVENTBRITE ",
            null,
            null,
            MarketplaceSort.RECOMMENDED,
            emptySet()
        )
        assertEquals("optional-eventbrite", result.single().id)
        val added = MarketplacePresentation.filterAndSort(
            presets,
            MarketplaceSection.PLUGINS,
            "",
            null,
            null,
            MarketplaceSort.ADDED_FIRST,
            setOf("optional-yelp")
        )
        assertEquals("optional-yelp", added.first().id)
    }

    @Test
    fun endpointAuthenticationIsNotInterchanged() {
        val geoapify = packages.single { it.provider == "geoapify" }.preset
        assertEquals("apiKey", geoapify.requiredEndpointQueryParameter)
        assertEquals("NONE", geoapify.suggestedAuthType)
        assertFalse(geoapify.hasRequiredEndpointParameters(geoapify.defaultEndpoint))
        assertEquals("OAUTH", packages.single { it.provider == "mapbox" }.preset.suggestedAuthType)
        assertTrue(packages.filter { it.runtime == MarketplaceRuntime.NATIVE }.all { it.preset.commandOrUrl == "builtin://marketplace/${it.provider}" })
    }

    @Test
    fun downloadsRejectMutableOrCredentialBearingUrls() {
        val valid = MarketplaceDownloadPolicy.assetUrl("README.md")
        MarketplaceDownloadPolicy.validateUrl(valid)
        listOf(
            valid.replace("https://", "http://"),
            valid + "?key=secret",
            valid + "#fragment",
            valid.replace("raw.githubusercontent.com", "raw.githubusercontent.com.evil.test"),
            valid.replace(GitHubMarketplaceCatalog.SOURCE_COMMIT, "main")
        ).forEach { url ->
            try {
                MarketplaceDownloadPolicy.validateUrl(url)
                fail("Unapproved URL was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected; the APK pins both the origin/path and immutable commit.
            }
        }
    }

    @Test
    fun checksumIsAnchoredAndSizeBounded() {
        val bytes = "abc".toByteArray()
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        MarketplaceDownloadPolicy.verify(bytes, digest)
        listOf(ByteArray(0), ByteArray(MarketplaceDownloadPolicy.MAX_ASSET_BYTES + 1), "changed".toByteArray()).forEach {
            try {
                MarketplaceDownloadPolicy.verify(it, digest)
                fail("Invalid bytes were accepted")
            } catch (_: IllegalArgumentException) {
                // Expected; no untrusted content should be written as an installed package.
            }
        }
    }
}
