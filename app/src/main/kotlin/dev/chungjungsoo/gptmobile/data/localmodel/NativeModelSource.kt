package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.channels.FileChannel
import kotlinx.coroutines.CancellationException

/** SAF read permission does not imply that a path-only native runtime can reopen the descriptor. */
internal object NativeModelSource {
    class CannotLink(val detail: String, cause: Exception? = null) :
        IllegalStateException(
            "This document can be read, but cannot be loaded in place. Choose Import copy for the selected document. No model copy was created. ($detail)",
            cause
        )

    fun <T> withFile(context: Context, uri: Uri, name: String, block: (File) -> T): T =
        (context.contentResolver.openFileDescriptor(uri, "r") ?: error("Source access was lost. Select it again.")).use {
            try {
                withDescriptor(context.cacheDir, it, name, block)
            } catch (failure: CannotLink) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "LocalImport",
                    "LINK_NATIVE_PATH_UNAVAILABLE · provider=${uri.authority} · reason=${failure.detail} · exception=${failure.cause?.javaClass?.simpleName.orEmpty()} · copyAvailable=true",
                    "W"
                )
                throw failure
            }
        }

    // The caller owns the descriptor. Neither a probe nor its temporary alias may close it.
    fun <T> withDescriptor(cache: File, descriptor: ParcelFileDescriptor, name: String, block: (File) -> T): T {
        require(LocalModelDownloadPaths.isValidPathSegment(name))
        val stat = Os.fstat(descriptor.fileDescriptor)
        require(stat.st_size <= 16_000_000_000L) { "This model exceeds the supported 16 GB file limit." }
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size !in 1..16_000_000_000L) throw CannotLink("non-seekable or unsupported size")
        val directory = File(cache, "model-link-check-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val alias = File(directory, name)
        try {
            Os.symlink("/proc/self/fd/${descriptor.fd}", alias.path)
            try {
                alias.inputStream().channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, minOf(4096L, stat.st_size)).get(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Keep the useful OS cause without exporting document paths or provider metadata.
                val errno = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
                throw CannotLink(if (errno == null) failure.javaClass.simpleName else "errno=$errno", failure)
            }
            return block(alias)
        } finally {
            alias.delete()
            directory.delete()
        }
    }
}
