package dev.chungjungsoo.gptmobile.data.voice

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.IdentityHashMap
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    shadows = [VoiceCompiledContextOsShadow::class]
)
class VoiceCompiledContextDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var cache: File

    @Before fun prepare() {
        cache = temporary.newFolder("cache")
        VoiceCompiledContextOsShadow.reset((Files.getAttribute(cache.toPath(), "unix:uid") as Number).toInt())
    }

    @After fun noDescriptorLeaks() {
        assertTrue(VoiceCompiledContextOsShadow.descriptors.isEmpty())
    }

    @Test fun createsAnOwnedPrivateDirectoryUsingNonblockingNoFollowDescriptor() {
        val result = VoiceCompiledContextDirectory.prepare(cache)
        assertEquals(directory().canonicalPath, result)
        assertEquals(0x1C0, mode(directory()))
        assertEquals(
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
            VoiceCompiledContextOsShadow.openFlags
        )
    }

    @Test fun repairsAnExistingGroupWritableDirectoryThroughItsDescriptor() {
        directory().mkdir()
        mode(directory(), 0x1FD)
        assertEquals(directory().canonicalPath, VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1C0, mode(directory()))
        assertEquals(1, VoiceCompiledContextOsShadow.chmodCalls)
    }

    @Test fun refusesASymbolicLinkWithoutChangingItsTarget() {
        val target = temporary.newFolder("target")
        mode(target, 0x1FD)
        Files.createSymbolicLink(directory().toPath(), target.toPath())
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(target))
        assertEquals(0, VoiceCompiledContextOsShadow.chmodCalls)
    }

    @Test fun refusesARegularFileBeforeChangingItsPermissions() {
        directory().writeText("keep")
        mode(directory(), 0x1B4)
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals("keep", directory().readText())
        assertEquals(0x1B4, mode(directory()))
        assertEquals(0, VoiceCompiledContextOsShadow.chmodCalls)
    }

    @Test fun refusesAnotherOwnerBeforeChangingPermissions() {
        directory().mkdir()
        mode(directory(), 0x1FD)
        VoiceCompiledContextOsShadow.foreignOwner = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(directory()))
        assertEquals(0, VoiceCompiledContextOsShadow.chmodCalls)
    }

    @Test fun deniedOrIneffectiveChmodDisablesOptionalPersistence() {
        directory().mkdir()
        mode(directory(), 0x1FD)
        VoiceCompiledContextOsShadow.denyChmod = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(directory()))
        VoiceCompiledContextOsShadow.denyChmod = false
        VoiceCompiledContextOsShadow.ignoreChmod = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(directory()))
    }

    @Test fun replacementAfterDescriptorChmodFailsTheFinalInodeCheck() {
        VoiceCompiledContextOsShadow.replaceAfterChmod = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertTrue(directory().isDirectory)
        assertEquals(0x1C0, mode(directory()))
    }

    private fun directory() = File(cache, "voice-compiled-contexts-v1")
    private fun mode(file: File): Int = (Files.getAttribute(file.toPath(), "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number).toInt() and 0xFFF
    private fun mode(file: File, value: Int) = Files.setAttribute(file.toPath(), "unix:mode", value, LinkOption.NOFOLLOW_LINKS)
}

/**
 * Explicit POSIX test boundary: default ShadowLinux cannot open directory descriptors
 * or fchmod them. Real temporary paths, permissions, symlinks and inode identities are
 * used here; descriptor ownership/faults are controlled and do not claim Android execution.
 *
 * Scope the public static Os calls instead of replacing Linux/BlockGuard instance shadows.
 * Robolectric can retain the platform's Linux object from an earlier ordinary test, whose
 * existing default shadow cannot be cast to a later test's custom instance shadow.
 */
@Implements(Os::class)
class VoiceCompiledContextOsShadow {
    companion object {
        val descriptors = IdentityHashMap<FileDescriptor, Path>()
        var owner = 0
        var openFlags = 0
        var chmodCalls = 0
        var foreignOwner = false
        var denyChmod = false
        var ignoreChmod = false
        var replaceAfterChmod = false

        @JvmStatic @Implementation
        fun mkdir(path: String, mode: Int) {
            try {
                Files.createDirectory(Paths.get(path))
            } catch (_: FileAlreadyExistsException) {
                throw ErrnoException("mkdir", OsConstants.EEXIST)
            }
            Files.setAttribute(Paths.get(path), "unix:mode", mode)
        }

        @JvmStatic @Implementation
        fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            openFlags = flags
            val target = Paths.get(path)
            if (Files.isSymbolicLink(target) && flags and OsConstants.O_NOFOLLOW != 0) throw ErrnoException("open", OsConstants.ELOOP)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw ErrnoException("open", OsConstants.ENOENT)
            return FileDescriptor().also { descriptors[it] = target }
        }

        @JvmStatic @Implementation
        fun fstat(descriptor: FileDescriptor): StructStat = attributes(checkNotNull(descriptors[descriptor]))

        @JvmStatic @Implementation
        fun lstat(path: String): StructStat = attributes(Paths.get(path))

        @JvmStatic @Implementation
        fun geteuid(): Int = owner

        @JvmStatic @Implementation
        fun fchmod(descriptor: FileDescriptor, mode: Int) {
            chmodCalls++
            if (denyChmod) throw ErrnoException("fchmod", OsConstants.EACCES)
            val path = checkNotNull(descriptors[descriptor])
            if (!ignoreChmod) Files.setAttribute(path, "unix:mode", mode)
            if (replaceAfterChmod) {
                val retired = path.resolveSibling("retired-context-directory")
                Files.move(path, retired)
                descriptors[descriptor] = retired
                Files.createDirectory(path)
                Files.setAttribute(path, "unix:mode", mode)
            }
        }

        @JvmStatic @Implementation
        fun close(descriptor: FileDescriptor) {
            checkNotNull(descriptors.remove(descriptor))
        }

        private fun attributes(path: Path): StructStat {
            val values = Files.readAttributes(path, "unix:*", LinkOption.NOFOLLOW_LINKS)
            fun number(key: String): Long = (values.getValue(key) as Number).toLong()
            return StructStat(
                number("dev"), number("ino"), number("mode").toInt(), number("nlink"),
                if (foreignOwner) owner + 1 else number("uid").toInt(), number("gid").toInt(), number("rdev"),
                number("size"), 0, 0, 0, 0, 0
            )
        }

        fun reset(uid: Int) {
            descriptors.clear()
            owner = uid
            openFlags = 0
            chmodCalls = 0
            foreignOwner = false
            denyChmod = false
            ignoreChmod = false
            replaceAfterChmod = false
        }
    }
}
