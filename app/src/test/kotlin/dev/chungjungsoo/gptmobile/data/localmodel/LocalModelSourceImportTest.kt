package dev.chungjungsoo.gptmobile.data.localmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class LocalModelSourceImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val repository = mockk<LocalModelRepository>()
    private val source = LocalModelSource(Uri.parse("content://provider/model"), LocalModelSource.Kind.FILE)
    private val job = "00000000-0000-0000-0000-000000000001"
    private val failure = LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.LINK_UNSUPPORTED, "Import copy is required")

    @Test fun oldAndFailedLinkRequestsNeverSilentlyCopy() = runBlocking {
        coEvery { repository.linkSource(source.uri, false, any(), any()) } returns failure
        assertEquals(LocalModelSource.Mode.LINK, source.mode)
        assertEquals(failure, LocalModelSourceImport.execute(context, repository, source, job, {}, {}))
        coVerify(exactly = 0) { repository.importPersisted(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { context.contentResolver }
    }

    @Test fun explicitCopyUsesTheGrantedStreamAndPersistedJobWithoutReopeningAPath() = runBlocking {
        var closed = false
        val stream = object : ByteArrayInputStream("LITERTLM".toByteArray()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        every { context.contentResolver } returns resolver
        every { resolver.query(source.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } answers { MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("model.litertlm")) } }
        every { resolver.openInputStream(source.uri) } returns stream
        coEvery { repository.importPersisted(stream, "model.litertlm", job, any(), any()) } coAnswers {
            assertEquals("LITERTLM", stream.readBytes().decodeToString())
            failure
        }
        assertEquals(failure, LocalModelSourceImport.execute(context, repository, source.copy(mode = LocalModelSource.Mode.COPY), job, {}, {}))
        assertTrue(closed)
        coVerify(exactly = 1) { repository.importPersisted(stream, "model.litertlm", job, any(), any()) }
        coVerify(exactly = 0) { repository.linkSource(any(), any(), any(), any()) }
        verify(exactly = 0) { resolver.openFileDescriptor(any(), any()) }
    }

    @Test fun folderCopyKeepsDeclaredBytesAndCleansItsArchiveAfterFailureAndCancellation() = runBlocking {
        val cache = temporary.newFolder()
        val tree = DocumentsContract.buildTreeDocumentUri("provider", "root")
        val folder = LocalModelSource(tree, LocalModelSource.Kind.FOLDER, LocalModelSource.Mode.COPY)
        val bytes = "GGUF-fixture".toByteArray()
        val manifest = ModelArtifactManifest(familyId = "fixture", artifactId = "fixture", runtime = ArtifactRuntime.GENIEX_LLAMA_CPP, format = "gguf", entryFile = "model.gguf", files = listOf(ArtifactFile("model.gguf", bytes.size.toLong(), PackageDigest.sha256(bytes))), contextTokens = 128, supportedBackends = setOf("cpu"))
        every { context.cacheDir } returns cache
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), null, null, null) } answers {
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply {
                addRow(arrayOf("manifest", "manifest.json"))
                addRow(arrayOf("weights", "model.gguf"))
            }
        }
        every { resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "manifest")) } answers { Json.encodeToString(ModelArtifactManifest.serializer(), manifest).byteInputStream() }
        every { resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "weights")) } answers { bytes.inputStream() }
        var cancel = false
        coEvery { repository.importPersisted(any(), any(), job, any(), any()) } coAnswers {
            ZipInputStream(firstArg<InputStream>()).use { zip ->
                assertEquals("manifest.json", zip.nextEntry.name)
                assertEquals(manifest, Json.decodeFromString(ModelArtifactManifest.serializer(), zip.readBytes().decodeToString()))
                assertEquals("model.gguf", zip.nextEntry.name)
                assertTrue(bytes.contentEquals(zip.readBytes()))
                assertEquals(null, zip.nextEntry)
            }
            if (cancel) throw CancellationException("fixture interruption")
            failure
        }
        // A restarted job replaces only its own incomplete staging archive.
        File(cache, "model-folder-$job.localmodel").writeText("interrupted archive")
        assertEquals(failure, LocalModelSourceImport.execute(context, repository, folder, job, {}, {}))
        assertTrue(cache.listFiles()!!.isEmpty())
        cancel = true
        val result = runCatching { LocalModelSourceImport.execute(context, repository, folder, job, {}, {}) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(cache.listFiles()!!.isEmpty())
        coVerify(exactly = 0) { repository.linkSource(any(), any(), any(), any()) }
    }
}
