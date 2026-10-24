package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Durable interruption evidence keyed by artifact/runtime/firmware/backend, not app install. */
internal class NativeOperationJournal(
    private val directory: File,
    private val exitCause: (Long, Int) -> String = { _, _ -> "UNKNOWN" },
    private val processId: () -> Int = { 0 }
) {
    private val pending get() = File(directory, "pending")
    private val metadata get() = File(directory, "pending-metadata")
    private fun digest(tuple: String) = MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun blocked(key: String) = File(directory, "quarantined-$key")

    /** Read-only, path-free quarantine diagnostics; never remove protection for an ordinary retry. */
    @Synchronized
    fun diagnostic(): String = "pending=${pending.isFile}; quarantined=${directory.listFiles()?.count { it.name.startsWith("quarantined-") } ?: 0}"

    @Synchronized
    fun before(tuple: String, stage: String = "NATIVE", requestId: String = "", generation: String = "") {
        check(directory.mkdirs() || directory.isDirectory)
        recoverInterrupted()
        val key = digest(tuple)
        check(!blocked(key).exists()) { "This artifact/runtime/backend was interrupted inside native code. In Local AI, open the profile menu and choose Verify load for this exact model/backend to perform a controlled isolated retry, or choose a compatible export/backend. Ordinary retries and app updates do not clear this restriction." }
        durableWrite(pending, key)
        durableWrite(metadata, listOf(System.currentTimeMillis(), processId(), stage.take(64), requestId.take(128), generation.take(128)).joinToString("\n"))
    }

    /** Advance the same operation; before() would mistake it for an interruption. */
    @Synchronized
    fun stage(stage: String, operationId: Long) {
        check(pending.isFile) { "Native operation has not been journaled" }
        val fields = metadata.readLines().toMutableList()
        while (fields.size < 6) fields += ""
        fields[2] = stage.take(64)
        fields[5] = operationId.toString()
        durableWrite(metadata, fields.joinToString("\n"))
    }

    private fun recoverInterrupted() {
        if (pending.isFile) {
            val interrupted = pending.readText()
            check(interrupted.matches(Regex("[a-f0-9]{64}"))) { "Native operation journal is damaged; reinstall or repair this runtime's data." }
            val fields = runCatching { metadata.readLines() }.getOrDefault(emptyList())
            val exitEvidence = exitCause(fields.getOrNull(0)?.toLongOrNull() ?: 0, fields.getOrNull(1)?.toIntOrNull() ?: 0)
            val cause = exitEvidence.substringBefore('|')
            // Low-memory and deliberate termination are scheduling events, not proof of bad weights.
            // Unknown interruptions remain protective until a controlled verification is performed.
            if (cause !in setOf("LOW_MEMORY", "USER_REQUESTED", "USER_STOPPED")) durableWrite(blocked(interrupted), "$interrupted\n$exitEvidence")
            durableWrite(File(directory, "last-interruption"), listOf(interrupted, exitEvidence).joinToString("\n"))
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                "NativeRuntime",
                "NATIVE_OPERATION_INTERRUPTED · stage=${fields.getOrNull(2)?.take(40) ?: "unknown"} · operation=${fields.getOrNull(5) ?: "unknown"} · exit=$exitEvidence · quarantined=${cause !in setOf("LOW_MEMORY", "USER_REQUESTED", "USER_STOPPED")} · exactTupleHash=${interrupted.take(12)}",
                if (cause == "LOW_MEMORY") "W" else "E"
            )
            check(pending.delete())
        }
    }

    @Synchronized
    fun isQuarantined(tuple: String): Boolean {
        recoverInterrupted()
        return blocked(digest(tuple)).exists()
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
        metadata.delete()
    }

    /** Explicit device verification only; ordinary retries never clear protective evidence. */
    @Synchronized
    fun allowVerification(tuple: String) {
        if (pending.exists()) {
            val previousPid = runCatching { metadata.readLines().getOrNull(1)?.toIntOrNull() }.getOrNull()
            check(previousPid != null && previousPid != processId()) { "A native operation is still pending" }
            recoverInterrupted()
        }
        val file = blocked(digest(tuple))
        if (file.exists()) {
            durableWrite(File(directory, "last-verification-reset"), file.readText())
            check(file.delete())
        }
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
