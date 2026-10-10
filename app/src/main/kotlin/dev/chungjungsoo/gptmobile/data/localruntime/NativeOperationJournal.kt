package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Durable interruption evidence keyed by artifact/runtime/firmware/backend, not app install. */
internal class NativeOperationJournal(private val directory: File) {
    private val pending get() = File(directory, "pending")
    private fun digest(tuple: String) = MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun blocked(key: String) = File(directory, "quarantined-$key")

    @Synchronized
    fun before(tuple: String) {
        check(directory.mkdirs() || directory.isDirectory)
        if (pending.isFile) {
            val interrupted = pending.readText()
            check(interrupted.matches(Regex("[a-f0-9]{64}"))) { "Native operation journal is damaged; reinstall or repair this runtime's data." }
            durableWrite(blocked(interrupted), interrupted)
            check(pending.delete())
        }
        val key = digest(tuple)
        check(!blocked(key).exists()) { "This artifact/runtime/backend was interrupted inside native code. Choose another compatible backend or a qualified artifact/runtime update before retrying. App updates alone do not clear this restriction." }
        durableWrite(pending, key)
    }

    @Synchronized
    fun quarantine(tuple: String) {
        check(directory.mkdirs() || directory.isDirectory)
        val key = digest(tuple)
        durableWrite(blocked(key), key)
    }

    @Synchronized
    fun finished() {
        pending.delete()
    }

    private fun durableWrite(target: File, content: String) {
        val temporary = File(directory, target.name + ".tmp")
        temporary.outputStream().use { output ->
            output.write(content.toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
