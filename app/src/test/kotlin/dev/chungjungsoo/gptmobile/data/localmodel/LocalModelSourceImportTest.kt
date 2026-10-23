package dev.chungjungsoo.gptmobile.data.localmodel

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
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
class LocalModelSourceImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val resolver = mockk<ContentResolver>()
    private val repository = mockk<LocalModelRepository>()
    private val uri = Uri.parse("content://stream-only-model/model")
    private val failure = LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.INVALID_MODEL, "fixture")

    private fun filename(name: String) {
        every { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        }
    }

    @Test fun defaultLinkDoesNotOpenOrCopyAStream() = runBlocking {
        coEvery { repository.linkSource(uri, false, any(), any()) } returns failure
        assertEquals(failure, importLocalModelSource(repository, resolver, LocalModelSource(uri, LocalModelSource.Kind.FILE), "job", {}, {}))
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
        coEvery { repository.importPersisted(any(), original.name, "durable-job", any(), any()) } coAnswers {
            LocalModelLocator.importModel(firstArg(), original.name, destination, stagingId = "durable-job", onProgress = arg(4))
        }
        var progress = 0L
        val result = importLocalModelSource(repository, resolver, LocalModelSource(uri, LocalModelSource.Kind.FILE, true), "durable-job", {}, { progress = it })
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
        val result = runCatching { importLocalModelSource(repository, resolver, LocalModelSource(uri, LocalModelSource.Kind.FILE, true), "job", {}, {}) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(closed)
    }

    @Test fun folderCopyIsRejectedBeforeAccessingTheSource() = runBlocking {
        val result = runCatching { importLocalModelSource(repository, resolver, LocalModelSource(uri, LocalModelSource.Kind.FOLDER, true), "job", {}, {}) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        verify { resolver wasNot Called }
        coVerify { repository wasNot Called }
    }

    @Test fun unsupportedAndUnsafeNamesAreRejectedBeforeCopying() = runBlocking {
        for (name in listOf("model.zip", "../model.gguf")) {
            filename(name)
            assertFalse(runCatching { importLocalModelSource(repository, resolver, LocalModelSource(uri, LocalModelSource.Kind.FILE, true), "job", {}, {}) }.isSuccess)
        }
        verify(exactly = 0) { resolver.openInputStream(any()) }
        coVerify { repository wasNot Called }
    }
}
