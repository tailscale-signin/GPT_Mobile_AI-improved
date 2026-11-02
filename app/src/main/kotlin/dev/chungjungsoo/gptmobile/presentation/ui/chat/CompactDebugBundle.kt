package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Streaming fallback: never claims that a recent log snapshot is the full conversation. */
internal object CompactDebugBundle {
    fun write(directory: File, chatId: Int, redactedLines: Sequence<String>): File {
        check(directory.mkdirs() || directory.isDirectory)
        val identity = UUID.randomUUID().toString()
        val destination = File(directory, "partial-diagnostics-$chatId-$identity.zip")
        val staging = File(directory, "$identity.partial")
        try {
            ZipOutputStream(staging.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(("{\"schema\":\"gptmobile.compact-diagnostics.v1\",\"chatId\":$chatId," +
                    "\"capturedFully\":false,\"reason\":\"JAVA_HEAP_PRESSURE\"," +
                    "\"scope\":\"recent_app_logs_only\",\"omitted\":[\"conversation\",\"tool_receipts\",\"research\",\"profiles\"]}").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("recent-app-logs.txt"))
                val writer = zip.bufferedWriter(Charsets.UTF_8, 4096)
                redactedLines.forEach { line -> writer.append(line).append('\n') }
                writer.flush()
                zip.closeEntry()
            }
            ZipFile(staging).use { zip ->
                check(zip.size() == 2)
                listOf("manifest.json", "recent-app-logs.txt").forEach { name ->
                    zip.getInputStream(checkNotNull(zip.getEntry(name))).use { input ->
                        val buffer = ByteArray(4096)
                        while (input.read(buffer) >= 0) Unit
                    }
                }
            }
            check(staging.renameTo(destination)) { "Unable to publish partial diagnostics" }
            return destination
        } finally {
            staging.delete()
        }
    }
}
