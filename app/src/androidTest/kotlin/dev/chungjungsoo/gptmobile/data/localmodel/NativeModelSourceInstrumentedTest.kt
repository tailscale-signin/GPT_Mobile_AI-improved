package dev.chungjungsoo.gptmobile.data.localmodel

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android permissions/mmap: host Robolectric descriptors cannot reproduce this restriction. */
@RunWith(AndroidJUnit4::class)
class NativeModelSourceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun readableDescriptorWithDeniedPathCanBeExplicitlyImported() {
        val root = File(context.cacheDir, "saf-regression-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val original = File(root, "restricted.litertlm")
        try {
            RandomAccessFile(original, "rw").use {
                it.write("LITERTLM".toByteArray())
                it.setLength(10L * 1024 * 1024)
            }
            val digest = PackageDigest.sha256(original)
            ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // Like a provider-owned fd: an existing grant can read, a new pathname open cannot.
                Os.chmod(original.path, 0)
                assertThrows(NativeModelSource.CannotLink::class.java) {
                    NativeModelSource.withDescriptor(root, descriptor, original.name) { error("Must not reach native loading") }
                }
                assertEquals(10L * 1024 * 1024, Os.fstat(descriptor.fileDescriptor).st_size)
                assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("model-link-check-") })
                val storage = File(root, "installed").apply { check(mkdirs()) }
                val result = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                    LocalModelLocator.importModel(it, original.name, storage)
                }
                assertTrue("$result", result is LocalModelImportResult.Success)
                val copy = File((result as LocalModelImportResult.Success).absoluteFilePath)
                assertEquals(digest, PackageDigest.sha256(copy))
                copy.inputStream().channel.use { assertEquals('L'.code.toByte(), it.map(FileChannel.MapMode.READ_ONLY, 0, 4096).get(0)) }
                assertEquals("The import must not change source permissions", 0, Os.stat(original.path).st_mode and 511)
                assertEquals(10L * 1024 * 1024, Os.fstat(descriptor.fileDescriptor).st_size)
            }
        } finally {
            Os.chmod(original.path, 384)
            root.deleteRecursively()
        }
    }

    @Test fun compatibleSourceRemainsInPlaceAndProbeDoesNotOwnItsDescriptor() {
        val root = File(context.cacheDir, "saf-link-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val original = File(root, "source.gguf").apply { writeText("GGUF") }
        try {
            ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                NativeModelSource.withDescriptor(root, descriptor, original.name) { assertEquals("GGUF", it.readText()) }
                assertEquals(4L, Os.fstat(descriptor.fileDescriptor).st_size)
                assertEquals(listOf(original), root.listFiles()!!.toList())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
