package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMediaReplyTest {
    @get:Rule val folder = TemporaryFolder()
    private val name = "2760215d-e8d0-4287-ba36-1c4dbd168aa7.png"

    @Test fun draftsOwnIndependentCopiesAndThePreviewSurvivesDiscard() {
        val media = folder.newFolder("media")
        val drafts = folder.newFolder("drafts")
        val source = File(media, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = requireNotNull(copyReplyImage(media, drafts, name))
        val second = requireNotNull(copyReplyImage(media, drafts, name))
        assertEquals("png", first.extension)
        assertNotEquals(first, second)
        assertArrayEquals(source.readBytes(), first.readBytes())
        assertTrue(first.delete())
        assertArrayEquals(source.readBytes(), second.readBytes())
    }

    @Test fun expiredInvalidOrOversizedMediaLeaveNoDraftFiles() {
        val media = folder.newFolder("media")
        val drafts = folder.newFolder("drafts")
        assertNull(copyReplyImage(media, drafts, name))
        assertNull(copyReplyImage(media, drafts, "../$name"))
        assertNull(copyReplyImage(media, drafts, name.replace(".png", ".mp3")))
        val source = File(media, name).apply { createNewFile() }
        assertNull(copyReplyImage(media, drafts, name))
        RandomAccessFile(source, "rw").use { it.setLength(4_194_305) }
        assertNull(copyReplyImage(media, drafts, name))
        assertTrue(drafts.listFiles().orEmpty().isEmpty())
    }

    @Test fun symbolicLinkCannotAttachAFileOutsideTheMediaCache() {
        val media = folder.newFolder("media")
        val drafts = folder.newFolder("drafts")
        val outside = folder.newFile("outside").apply { writeText("private") }
        Files.createSymbolicLink(File(media, name).toPath(), outside.toPath())
        assertNull(copyReplyImage(media, drafts, name))
        assertTrue(drafts.listFiles().orEmpty().isEmpty())
    }
}
