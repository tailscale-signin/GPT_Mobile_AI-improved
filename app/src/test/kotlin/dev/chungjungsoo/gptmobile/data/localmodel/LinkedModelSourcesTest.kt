package dev.chungjungsoo.gptmobile.data.localmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.chungjungsoo.gptmobile.data.localruntime.localModelAssetBytes
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real regular-file descriptors and hashing; the document-provider permission boundary is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class LinkedModelSourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var original: File
    private lateinit var appFiles: File
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private val uri = Uri.parse("content://model-source-fixture/model")
    private var permitted = true

    @Before fun prepare() {
        original = File(temporary.newFolder("original"), "sample.litertlm")
        RandomAccessFile(original, "rw").use {
            it.write("LITERTLM".toByteArray())
            it.setLength(10L * 1024 * 1024)
        }
        appFiles = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val noBackup = temporary.newFolder("no-backup")
        context = mockk()
        resolver = mockk()
        val grant = mockk<UriPermission>()
        every { grant.uri } returns uri
        every { grant.isReadPermission } returns true
        every { context.applicationContext } returns context
        every { context.filesDir } returns appFiles
        every { context.cacheDir } returns cache
        every { context.noBackupFilesDir } returns noBackup
        every { context.getExternalFilesDir(null) } returns null
        every { context.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } answers { if (permitted) listOf(grant) else emptyList() }
        every { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } answers { MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(original.name)) } }
        every { resolver.openFileDescriptor(uri, "r") } answers { ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY) }
        LinkedModelSources.closeLeases()
        LinkedModelSources.initialize(context)
    }

    @After fun close() = LinkedModelSources.closeLeases()

    private fun link(): LocalModelImportResult.Success {
        val result = LocalModelReferenceInstaller.install(context, uri, false, appFiles, {}, {})
        assertTrue("Link must succeed: $result", result is LocalModelImportResult.Success)
        return result as LocalModelImportResult.Success
    }

    @Test fun linksWithoutCopyingWeightsAndReopensFromDurableReceipt() {
        val linked = link()
        val marker = File(linked.absoluteFilePath)
        assertTrue(marker.length() < 1024)
        assertTrue(appFiles.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 1024 * 1024)
        assertEquals(original.length(), localModelAssetBytes(marker))
        assertEquals(PackageDigest.sha256(original), PackageDigest.sha256(LinkedModelSources.resolveFile(marker)))
        LinkedModelSources.closeLeases()
        LinkedModelSources.initialize(context)
        assertEquals(original.length(), LinkedModelSources.resolveFile(marker).length())
    }

    @Test fun revocationAndSameSizeEditsCannotReuseAnOldDescriptor() {
        val marker = File(link().absoluteFilePath)
        LinkedModelSources.resolveFile(marker)
        permitted = false
        assertTrue(runCatching { LinkedModelSources.resolveFile(marker) }.isFailure)
        permitted = true
        val modified = original.lastModified()
        RandomAccessFile(original, "rw").use {
            it.seek(100)
            it.writeByte(7)
        }
        original.setLastModified(modified)
        assertTrue(runCatching { LinkedModelSources.resolveFile(marker) }.isFailure)
        assertTrue(original.isFile)
    }

    @Test fun unlinkRemovesOnlyAppMetadataAndMarketplaceFilesKeepTheirPath() {
        val marker = File(link().absoluteFilePath)
        val digest = PackageDigest.sha256(original)
        LinkedModelSources.resolveFile(marker)
        LinkedModelSources.forget(marker.parentFile!!)
        marker.parentFile!!.deleteRecursively()
        assertEquals(digest, PackageDigest.sha256(original))
        val marketplace = File(appFiles, "marketplace.litertlm").apply { writeText("managed download") }
        assertEquals(marketplace, LinkedModelSources.resolveFile(marketplace))
    }

    @Test fun manifestAssetsResolveOnlyDeclaredSafePathsAndKeepTheirFullSize() {
        RandomAccessFile(original, "rw").use { it.write("GGUF".toByteArray()) }
        val root = File(appFiles, "models/linked-fixture/local").apply { check(mkdirs()) }
        val hash = PackageDigest.sha256(original)
        LinkedModelSources.register(root, uri, listOf(LinkedModelAsset("sub/model.gguf", uri.toString(), original.length(), hash)))
        val manifest = ModelArtifactManifest(schemaVersion = 3, familyId = "fixture", artifactId = "linked-fixture", runtime = ArtifactRuntime.LLAMA_CPP, format = "gguf", entryFile = "sub/model.gguf", files = listOf(ArtifactFile("sub/model.gguf", original.length(), hash)), contextTokens = 128, supportedBackends = setOf("cpu"), runtimeVersion = dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.GGUF_REVISION)
        val file = File(root, "manifest.json")
        ArtifactManifestStore.write(file, manifest)
        manifest.validate(root)
        assertEquals(original.length(), localModelAssetBytes(file))
        assertTrue(ModelArtifactManifest.safeFile(root, manifest.entryFile).path.endsWith("sub/model.gguf"))
        assertTrue(runCatching { ModelArtifactManifest.safeFile(root, "../model.gguf") }.isFailure)
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 1024 * 1024)
    }

    @Test fun compressedSourcesAreRejectedWithoutAnImplicitCopy() {
        original = File(original.parentFile, "source.zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }
        val result = LocalModelReferenceInstaller.install(context, uri, false, appFiles, {}, {})
        assertTrue(result is LocalModelImportResult.Failure)
        assertTrue((result as LocalModelImportResult.Failure).message.contains("Extract"))
        assertTrue(appFiles.walkTopDown().none { it.isFile })
        assertTrue(original.isFile)
    }
}
