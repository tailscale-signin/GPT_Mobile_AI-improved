package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A private compiler/dispatch directory must also contain the matching QNN dependencies. */
internal object PocketCompiledLibraryLayout {
    data class Library(val bytes: Long, val sha256: String, val open: () -> InputStream)

    fun prepare(directory: File, libraries: Map<String, Library>, identityCache: File, checkActive: () -> Unit): File {
        require(libraries.isNotEmpty()) { "POCKET_NPU_LIBRARY_INVENTORY" }
        require(
            libraries.all { (name, library) ->
                name.matches(Regex("lib[A-Za-z0-9_]+\\.so")) && library.bytes in 1..268_435_456L && library.sha256.matches(Regex("[a-f0-9]{64}"))
            }
        ) { "POCKET_NPU_LIBRARY_INVENTORY" }
        checkActive()
        check(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdirs())) { "POCKET_NPU_LIBRARY_DIRECTORY" }
        for ((name, library) in libraries) {
            checkActive()
            val destination = File(directory, name)
            if (!Files.isSymbolicLink(destination.toPath()) &&
                destination.isFile &&
                destination.length() == library.bytes &&
                PackageDigest.journalIdentityObserved(destination, identityCache, checkActive).digest == library.sha256
            ) {
                continue
            }
            val temporary = File.createTempFile(name, ".part", directory)
            try {
                library.open().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        var written = 0L
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            check(written <= library.bytes) { "POCKET_NPU_LIBRARY_SIZE" }
                            output.write(buffer, 0, count)
                        }
                        check(written == library.bytes) { "POCKET_NPU_LIBRARY_SIZE" }
                        output.fd.sync()
                    }
                }
                check(PackageDigest.sha256(temporary, checkActive) == library.sha256) { "POCKET_NPU_LIBRARY_DIGEST" }
                check(temporary.setReadOnly()) { "POCKET_NPU_LIBRARY_PERMISSIONS" }
                checkActive()
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
        }
        checkActive()
        return directory
    }
}
