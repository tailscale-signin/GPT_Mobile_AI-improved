package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A receipt of our intent to terminate; only a matching OS exit confirms it happened. */
internal object NativeWorkerTermination {
    @Synchronized fun record(directory: File, pid: Int, requestId: String, generation: String, reason: String, progress: GgufNativeProgress?, nowMs: Long = System.currentTimeMillis()) {
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "worker-termination-$pid")
        val temporary = File(directory, "worker-termination-$pid.tmp")
        val fields = listOf(nowMs, pid, requestId.take(128), generation.take(128), reason.take(80), progress?.operationId ?: -1, progress?.stage.orEmpty().take(80))
        temporary.outputStream().use { output ->
            output.write(fields.joinToString("\n", postfix = "\n").toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        directory.listFiles()?.filter { it.name.startsWith("worker-termination-") && it != file }?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { it.delete() }
    }

    fun snapshot(directory: File): List<Map<String, String>> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("worker-termination-[0-9]+")) && it.isFile && it.length() <= 1024 }
        .sortedByDescending { it.lastModified() }.take(16).mapNotNull { file ->
            runCatching {
                val fields = file.readLines()
                if (fields.size != 7 || fields[0].toLongOrNull() == null || fields[1].toIntOrNull() == null) return@runCatching null
                listOf("time", "process", "request", "generation", "reason", "operation", "stage").zip(fields).toMap()
            }.getOrNull()
        }

    fun matched(directory: File, pid: Int, sinceMs: Long, exitAtMs: Long): String? = runCatching {
        val file = File(directory, "worker-termination-$pid")
        if (!file.isFile || file.length() > 1024) return null
        val fields = file.readLines()
        val at = fields.firstOrNull()?.toLongOrNull() ?: return null
        if (fields.getOrNull(1)?.toIntOrNull() != pid || at < sinceMs || exitAtMs - at !in 0..15_000) return null
        "CONTROLLED_TERMINATION|initiator=app|reason=${fields.getOrNull(4)}|request=${fields.getOrNull(2)}|generation=${fields.getOrNull(3)}|operation=${fields.getOrNull(5)}|stage=${fields.getOrNull(6)}"
    }.getOrNull()
}
