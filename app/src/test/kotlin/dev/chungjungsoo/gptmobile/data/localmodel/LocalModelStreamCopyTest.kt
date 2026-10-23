package dev.chungjungsoo.gptmobile.data.localmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class LocalModelStreamCopyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>().also { every { it.contentResolver } returns resolver }
    private val repository = mockk<LocalModelRepository>()
    private val uri = Uri.parse("content://stream-only-model/model")
    private val failure = LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.INVALID_MODEL, "fixture")

    @Test fun persistedImportChoicesSurviveTheModeMigration() {
        assertEquals(LocalModelSource.Mode.LINK, localModelImportMode(null, false))
        assertEquals(LocalModelSource.Mode.COPY, localModelImportMode(null, true))
        assertEquals(LocalModelSource.Mode.LINK, localModelImportMode("LINK", true))
        assertEquals(LocalModelSource.Mode.COPY, localModelImportMode("COPY", false))
        assertTrue(runCatching { localModelImportMode("INVALID", false) }.isFailure)
    }

    private fun filename(name: String) {
        every { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        }
    }

    @Test fun defaultLinkDoesNotOpenOrCopyAStream() = runBlocking {
        coEvery { repository.linkSource(uri, false, any(), any()) } returns failure
        assertEquals(failure, LocalModelSourceImport.execute(context, repository, LocalModelSource(uri, LocalModelSource.Kind.FILE), "job", {}, {}))
        coVerify(exactly = 1) { repository.linkSource(uri, false, any(), any()) }
        verify { resolver wasNot Called }
    }

    @Test fun copyWorksWithAReadableStreamWhenNativeReopeningIsUnavailable() = runBlocking {
        val original = File(temporary.newFolder(), "model.litertlm")
        RandomAccessFile(original, "rw").use {
            it.write("LITERTLM".toByteArray())
            it.setLength(10L * 1024 * 1024)
        }
        val digest = PackageDigest.sha256(original)
        val destination = temporary.newFolder()
        filename(original.name)
        every { resolver.openInputStream(uri) } answers { original.inputStream() }
        every { resolver.openFileDescriptor(uri, "r") } throws SecurityException("Native reopen denied")
        val jobId = "12345678-1234-1234-1234-123456789abc"
        coEvery { repository.importPersisted(any(), original.name, jobId, any(), any()) } coAnswers {
            LocalModelLocator.importModel(firstArg(), original.name, destination, stagingId = jobId, onProgress = arg(4))
        }
        var progress = 0L
        val result = LocalModelSourceImport.execute(context, repository, LocalModelSource(uri, LocalModelSource.Kind.FILE, LocalModelSource.Mode.COPY), jobId, {}, { progress = it })
        assertTrue(result is LocalModelImportResult.Success)
        val installed = File((result as LocalModelImportResult.Success).absoluteFilePath)
        assertTrue(installed.canonicalPath.startsWith(destination.canonicalPath + "/"))
        assertEquals(digest, PackageDigest.sha256(installed))
        assertEquals(digest, PackageDigest.sha256(original))
        assertEquals(original.length(), progress)
        verify(exactly = 0) { resolver.openFileDescriptor(any(), any()) }
        coVerify(exactly = 0) { repository.linkSource(any(), any(), any(), any()) }
        assertTrue(File(destination, "models").listFiles().orEmpty().none { it.name.startsWith(".import-") })
    }

    @Test fun ggufCopyUsesThePersistedInstallerAndClosesTheStreamOnCancellation() = runBlocking {
        filename("model.gguf")
        var closed = false
        val input = object : ByteArrayInputStream("GGUF".toByteArray()) {
            override fun close() {
                closed = true
            }
        }
        every { resolver.openInputStream(uri) } returns input
        coEvery { repository.importPersisted(input, "model.gguf", "job", any(), any()) } throws CancellationException("cancelled")
        val result = runCatching { LocalModelSourceImport.execute(context, repository, LocalModelSource(uri, LocalModelSource.Kind.FILE, LocalModelSource.Mode.COPY), "job", {}, {}) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(closed)
    }

    @Test fun unsupportedAndUnsafeNamesAreRejectedBeforeCopying() = runBlocking {
        for (name in listOf("model.zip", "../model.gguf")) {
            filename(name)
            assertFalse(runCatching { LocalModelSourceImport.execute(context, repository, LocalModelSource(uri, LocalModelSource.Kind.FILE, LocalModelSource.Mode.COPY), "job", {}, {}) }.isSuccess)
        }
        verify(exactly = 0) { resolver.openInputStream(any()) }
        coVerify { repository wasNot Called }
    }
}
