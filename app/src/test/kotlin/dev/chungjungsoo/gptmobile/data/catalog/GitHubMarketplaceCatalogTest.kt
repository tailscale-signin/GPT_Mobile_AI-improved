package dev.chungjungsoo.gptmobile.data.catalog

import dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceDownloadPolicy
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubMarketplaceCatalogTest {
    private val packages = GitHubMarketplaceCatalog.packages

    @Test fun marketplaceSurfacesOnlyUsableEntriesAndEveryEntryHasAWebsite() {
        val all = packages.map { it.preset } + McpPresetCatalog.presets
        MarketplaceSection.entries.forEach { section ->
            val visible = MarketplacePresentation.filterAndSort(all, section, "", null, null, MarketplaceSort.RECOMMENDED, emptySet())
            assertTrue(visible.none { it.documentationOnly })
            assertTrue(visible.all { java.net.URI(it.websiteLink).host.isNotBlank() })
        }
        val imported = packages.first().preset.copy(websiteUrl = "", commandOrUrl = "https://user:secret@mcp.example.org/mcp?token=private")
        assertEquals("https://mcp.example.org/", imported.websiteLink)
    }

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

    @Test
    fun packageManifestDoesNotBindMutablePresentationCopy() {
        val entry = packages.first { it.runtime == MarketplaceRuntime.NATIVE }
        val changedCopy = entry.copy(
            preset = entry.preset.copy(setupInstructions = "Updated setup copy", description = "Updated description"),
            serviceNotice = "Updated notice"
        )
        val first = MarketplaceDownloadPolicy.manifest(entry)
        val second = MarketplaceDownloadPolicy.manifest(changedCopy)

        assertEquals(first.toList(), second.toList())
        val json = first.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"schemaVersion\":2"))
        assertTrue(json.contains("\"packageRevision\":1"))
        assertFalse(json.contains("setupInstructions"))
        assertFalse(json.contains("serviceNotice"))
    }

    @Test
    fun packageVerificationAcceptsTrustedLegacyIdentityButRejectsUnlistedFiles() {
        val entry = packages.first { it.runtime == MarketplaceRuntime.NATIVE }
        val setup = """{"schemaVersion":1,"id":"${entry.id}","provider":"${entry.provider}","runtime":"${entry.runtime.name}","sourceRepository":"${GitHubMarketplaceCatalog.SOURCE_REPOSITORY}","sourceCommit":"${GitHubMarketplaceCatalog.SOURCE_COMMIT}","setup":"old user facing copy"}"""
        val trusted = zipOf(
            "setup.json" to setup.toByteArray(),
            "README.md" to java.io.File("mcp/marketplace/README.md").readBytes()
        )
        assertEquals(MarketplaceDownloadPolicy.Verification.LEGACY_TRUSTED, MarketplaceDownloadPolicy.verifyPackage(entry, trusted))

        val withUnlistedFile = zipOf(
            "setup.json" to setup.toByteArray(),
            "README.md" to java.io.File("mcp/marketplace/README.md").readBytes(),
            "payload.txt" to "untrusted".toByteArray()
        )
        try {
            MarketplaceDownloadPolicy.verifyPackage(entry, withUnlistedFile)
            fail("Unlisted package content was accepted")
        } catch (_: IllegalArgumentException) {
            // Archive contents must match the exact APK-pinned allowlist.
        }
    }

    @Test
    fun currentManifestRequiresTheExactContractFields() {
        val entry = packages.first { it.runtime == MarketplaceRuntime.NATIVE }
        val manifest = MarketplaceDownloadPolicy.manifest(entry)
        val readme = java.io.File("mcp/marketplace/README.md").readBytes()
        assertEquals(
            MarketplaceDownloadPolicy.Verification.CURRENT,
            MarketplaceDownloadPolicy.verifyPackage(entry, zipOf("manifest.json" to manifest, "README.md" to readme))
        )

        val extended = (manifest.toString(Charsets.UTF_8).dropLast(1) + ",\"untrusted\":true}").toByteArray()
        try {
            MarketplaceDownloadPolicy.verifyPackage(entry, zipOf("manifest.json" to extended, "README.md" to readme))
            fail("Unexpected manifest field was accepted")
        } catch (_: IllegalArgumentException) {
            // The V2 package contract is closed and versioned.
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }
}
