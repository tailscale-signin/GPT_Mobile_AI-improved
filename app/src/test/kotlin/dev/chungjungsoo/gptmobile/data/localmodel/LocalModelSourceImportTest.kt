package dev.chungjungsoo.gptmobile.data.localmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class LocalModelSourceImportTest {
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
}
