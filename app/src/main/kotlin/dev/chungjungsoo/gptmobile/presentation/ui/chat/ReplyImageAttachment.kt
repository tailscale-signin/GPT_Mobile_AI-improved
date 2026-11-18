package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.io.File
import java.io.IOException

private val replyImageName = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(?:png|jpg|webp)")

/** Give the composer an independent attachment, so discarding a draft keeps its original preview. */
internal fun copyReplyImage(mediaDirectory: File, attachments: File, name: String): File? {
    if (!replyImageName.matches(name)) return null
    var staging: File? = null
    return try {
        val source = File(mediaDirectory, name)
        if (!source.isFile || source.canonicalFile != File(mediaDirectory.canonicalFile, name) || source.length() !in 1L..4_194_304L) return null
        if (!attachments.isDirectory && !attachments.mkdirs()) return null
        val target = File.createTempFile("reply-image-", "." + source.extension, attachments)
        staging = target
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        if (target.length() != source.length() || target.length() !in 1L..4_194_304L) return null
        staging = null
        target
    } catch (_: IOException) {
        null
    } finally {
        staging?.delete()
    }
}
