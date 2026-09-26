package dev.chungjungsoo.gptmobile.data.knowledge

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.util.DocumentTextExtractor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class MemoryAttachment(val chatId: Int, val messageId: Int, val attachment: ChatAttachment) {
    val documentId get() = MemoryDocumentRepository.documentId(chatId.toString(), attachment.resolvedDisplayName, File(attachment.filePathForDisplay).name)
}

data class DocumentIndexResult(val indexed: Int, val skipped: Int)

@Singleton
class AttachmentLibraryRepository @Inject constructor(
    database: ChatDatabaseV2,
    private val documents: MemoryDocumentRepository,
    @ApplicationContext private val context: Context
) {
    val attachments = database.messageDao().observeAttachments().map { messages ->
        messages.flatMap { message -> message.attachments.map { MemoryAttachment(message.chatId, message.id, it) } }
            .distinctBy { it.chatId to it.attachment.filePathForDisplay }
    }

    suspend fun indexAll(): DocumentIndexResult = withContext(Dispatchers.IO) {
        var indexed = 0
        var skipped = 0
        attachments.first().filterNot { it.attachment.mimeType.startsWith("image/") }.forEach { entry ->
            val attachment = entry.attachment
            try {
                val text = attachment.extractedText ?: DocumentTextExtractor.extract(context, File(attachment.filePathForDisplay), attachment.mimeType).text
                if (text.isNotBlank()) {
                    documents.index(attachment.resolvedDisplayName, text.take(1_000_000), chatId = entry.chatId, sourceKey = File(attachment.filePathForDisplay).name, explicitlyRestore = true)
                    indexed++
                } else {
                    skipped++
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                skipped++
            }
        }
        DocumentIndexResult(indexed, skipped)
    }
}
