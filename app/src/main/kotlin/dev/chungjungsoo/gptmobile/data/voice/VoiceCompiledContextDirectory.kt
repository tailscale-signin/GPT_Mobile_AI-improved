package dev.chungjungsoo.gptmobile.data.voice

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.IOException

/** Optional graph persistence requires a private, owned directory before native admission. */
internal object VoiceCompiledContextDirectory {
    private const val NAME = "voice-compiled-contexts-v1"
    private const val PRIVATE_MODE = 0x1C0
    private const val PERMISSION_MASK = 0xFFF

    fun prepare(cacheDirectory: File): String = try {
        prepareOwned(cacheDirectory)
    } catch (_: IOException) {
        ""
    } catch (_: ErrnoException) {
        ""
    } catch (_: SecurityException) {
        ""
    }

    private fun prepareOwned(cacheDirectory: File): String {
        val parent = cacheDirectory.canonicalFile
        val directory = File(parent, NAME)
        if (!sameParent(directory, parent)) return ""
        try {
            Os.mkdir(directory.path, PRIVATE_MODE)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EEXIST) throw failure
        }
        // O_DIRECTORY is not exposed by the public Android API. O_NONBLOCK avoids
        // blocking on a substituted FIFO; fstat checks the type before any mutation.
        val descriptor = Os.open(
            directory.path,
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
            0
        )
        try {
            val owner = Os.geteuid()
            val opened = Os.fstat(descriptor)
            if (!ownedDirectory(opened, owner)) return ""
            Os.fchmod(descriptor, PRIVATE_MODE)
            val secured = Os.fstat(descriptor)
            if (!ownedDirectory(secured, owner) || secured.st_mode and PERMISSION_MASK != PRIVATE_MODE) return ""
            val current = Os.lstat(directory.path)
            if (!ownedDirectory(current, owner) || current.st_mode and PERMISSION_MASK != PRIVATE_MODE) return ""
            if (opened.st_dev != secured.st_dev ||
                opened.st_ino != secured.st_ino ||
                current.st_dev != secured.st_dev ||
                current.st_ino != secured.st_ino ||
                !sameParent(directory, parent)
            ) {
                return ""
            }
            return directory.path
        } finally {
            Os.close(descriptor)
        }
    }

    private fun ownedDirectory(stat: StructStat, owner: Int): Boolean = OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == owner

    private fun sameParent(directory: File, parent: File): Boolean {
        val canonical = directory.canonicalFile
        return canonical == directory.absoluteFile && canonical.parentFile == parent
    }
}
