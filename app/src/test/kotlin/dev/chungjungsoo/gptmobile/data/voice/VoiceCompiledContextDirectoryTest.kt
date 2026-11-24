package dev.chungjungsoo.gptmobile.data.voice

import android.system.ErrnoException
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
import org.robolectric.shadows.ShadowBlockGuardOs
import org.robolectric.shadows.ShadowLinux

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    shadows = [VoiceCompiledContextLinuxShadow::class, VoiceCompiledContextBlockGuardShadow::class]
)
class VoiceCompiledContextDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var cache: File

    @Before fun prepare() {
        cache = temporary.newFolder("cache")
        VoiceCompiledContextLinuxShadow.reset((Files.getAttribute(cache.toPath(), "unix:uid") as Number).toInt())
    }

    @After fun noDescriptorLeaks() {
        assertTrue(VoiceCompiledContextLinuxShadow.descriptors.isEmpty())
    }

    @Test fun createsAnOwnedPrivateDirectoryUsingNonblockingNoFollowDescriptor() {
        val result = VoiceCompiledContextDirectory.prepare(cache)
        assertEquals(directory().canonicalPath, result)
        assertEquals(0x1C0, mode(directory()))
        assertEquals(
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
            VoiceCompiledContextLinuxShadow.openFlags
        )
    }

    @Test fun repairsAnExistingGroupWritableDirectoryThroughItsDescriptor() {
        directory().mkdir()
        mode(directory(), 0x1FD)
        assertEquals(directory().canonicalPath, VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1C0, mode(directory()))
        assertEquals(1, VoiceCompiledContextLinuxShadow.chmodCalls)
    }

    @Test fun refusesASymbolicLinkWithoutChangingItsTarget() {
        val target = temporary.newFolder("target")
        mode(target, 0x1FD)
        Files.createSymbolicLink(directory().toPath(), target.toPath())
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(target))
        assertEquals(0, VoiceCompiledContextLinuxShadow.chmodCalls)
    }

    @Test fun refusesARegularFileBeforeChangingItsPermissions() {
        directory().writeText("keep")
        mode(directory(), 0x1B4)
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals("keep", directory().readText())
        assertEquals(0x1B4, mode(directory()))
        assertEquals(0, VoiceCompiledContextLinuxShadow.chmodCalls)
    }

    @Test fun refusesAnotherOwnerBeforeChangingPermissions() {
        directory().mkdir()
        mode(directory(), 0x1FD)
        VoiceCompiledContextLinuxShadow.foreignOwner = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(directory()))
        assertEquals(0, VoiceCompiledContextLinuxShadow.chmodCalls)
    }

    @Test fun deniedOrIneffectiveChmodDisablesOptionalPersistence() {
        directory().mkdir()
        mode(directory(), 0x1FD)
        VoiceCompiledContextLinuxShadow.denyChmod = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(directory()))
        VoiceCompiledContextLinuxShadow.denyChmod = false
        VoiceCompiledContextLinuxShadow.ignoreChmod = true
        assertEquals("", VoiceCompiledContextDirectory.prepare(cache))
        assertEquals(0x1FD, mode(directory()))
    }

    @Test fun replacementAfterDescriptorChmodFailsTheFinalInodeCheck() {
        VoiceCompiledContextLinuxShadow.replaceAfterChmod = true
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
 */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
class VoiceCompiledContextLinuxShadow : ShadowLinux() {
    @Implementation override fun mkdir(path: String, mode: Int) {
        try {
            Files.createDirectory(Paths.get(path))
        } catch (_: FileAlreadyExistsException) {
            throw ErrnoException("mkdir", OsConstants.EEXIST)
        }
        Files.setAttribute(Paths.get(path), "unix:mode", mode)
    }

    @Implementation override fun open(path: String, flags: Int, mode: Int): FileDescriptor {
        openFlags = flags
        val target = Paths.get(path)
        if (Files.isSymbolicLink(target) && flags and OsConstants.O_NOFOLLOW != 0) throw ErrnoException("open", OsConstants.ELOOP)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw ErrnoException("open", OsConstants.ENOENT)
        return FileDescriptor().also { descriptors[it] = target }
    }

    @Implementation override fun fstat(descriptor: FileDescriptor): StructStat = attributes(checkNotNull(descriptors[descriptor]))

    @Implementation override fun lstat(path: String): StructStat = attributes(Paths.get(path))

    @Implementation fun geteuid(): Int = owner

    @Implementation fun fchmod(descriptor: FileDescriptor, mode: Int) {
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

    @Implementation fun close(descriptor: FileDescriptor) = releaseDescriptor(descriptor)

    private fun attributes(path: Path): StructStat {
        val values = Files.readAttributes(path, "unix:*", LinkOption.NOFOLLOW_LINKS)
        fun number(key: String): Long = (values.getValue(key) as Number).toLong()
        return StructStat(
            number("dev"), number("ino"), number("mode").toInt(), number("nlink"),
            if (foreignOwner) owner + 1 else number("uid").toInt(), number("gid").toInt(), number("rdev"),
            number("size"), 0, 0, 0, 0, 0
        )
    }

    companion object {
        val descriptors = IdentityHashMap<FileDescriptor, Path>()
        var owner = 0
        var openFlags = 0
        var chmodCalls = 0
        var foreignOwner = false
        var denyChmod = false
        var ignoreChmod = false
        var replaceAfterChmod = false

        fun releaseDescriptor(descriptor: FileDescriptor) {
            checkNotNull(descriptors.remove(descriptor))
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

/** Robolectric's default BlockGuard close is a no-op; exercise the helper's real finally path. */
@Implements(className = "libcore.io.BlockGuardOs", isInAndroidSdk = false)
class VoiceCompiledContextBlockGuardShadow : ShadowBlockGuardOs() {
    @Implementation override fun close(descriptor: FileDescriptor) = VoiceCompiledContextLinuxShadow.releaseDescriptor(descriptor)
}
