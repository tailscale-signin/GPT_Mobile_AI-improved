package dev.chungjungsoo.gptmobile.data.amazon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AmazonProductMedia(val product: JsonObject, val image: ByteArray? = null, val detailed: Boolean = false)

internal enum class AmazonMediaConversation { ACTIVE, TEMPORARY, UNAVAILABLE }

/** Retains original image bytes with the conversation; decoded bitmaps and the network cache stay bounded. */
@Singleton
class AmazonProductMediaCache internal constructor(
    private val root: File,
    private val conversation: suspend (Int) -> AmazonMediaConversation,
    private val images: AmazonProductImageProvider
) {
    @Inject constructor(@ApplicationContext context: Context, chats: ChatRoomV2Dao, images: AmazonProductImageProvider) : this(
        File(context.filesDir, "amazon-product-media"),
        { id ->
            val room = chats.getChatRoomsByIds(listOf(id)).firstOrNull()
            when {
                room == null || room.isArchived -> AmazonMediaConversation.UNAVAILABLE
                room.isTemporary -> AmazonMediaConversation.TEMPORARY
                else -> AmazonMediaConversation.ACTIVE
            }
        },
        images
    )

    private val lock = Mutex()

    suspend fun available(chatId: Int?): Boolean = chatId == null || conversation(chatId) != AmazonMediaConversation.UNAVAILABLE

    suspend fun load(chatId: Int?, product: JsonObject): AmazonProductMedia? = withContext(Dispatchers.IO) {
        if (chatId == null) return@withContext null
        lock.withLock {
            if (conversation(chatId) != AmazonMediaConversation.ACTIVE) return@withLock null
            runCatching {
                val directory = directory(chatId, product) ?: return@withLock null
                val metadata = File(directory, "product.json").takeIf { it.isFile && it.length() <= 100_000 } ?: return@withLock null
                val data = Json.parseToJsonElement(metadata.readText()) as? JsonObject ?: return@withLock null
                val retained = data["product"] as? JsonObject ?: return@withLock null
                val enriched = AmazonProducts.withDetails(product, retained)
                if (key(product) != key(retained)) return@withLock null
                val photo = File(directory, "image").takeIf { it.isFile && it.length() in 1..3 * 1_048_576L }
                    ?.readBytes()?.takeIf(AmazonProductImageProvider::validImage)
                AmazonProductMedia(enriched, photo, (data["detailed"] as? JsonPrimitive)?.booleanOrNull == true)
            }.getOrNull()
        }
    }

    suspend fun save(chatId: Int?, media: AmazonProductMedia) = withContext(Dispatchers.IO) {
        if (chatId == null) return@withContext
        lock.withLock {
            // Recheck under the same lock as archive cleanup, including when a download finishes late.
            if (conversation(chatId) != AmazonMediaConversation.ACTIVE) return@withLock
            runCatching {
                val directory = directory(chatId, media.product) ?: return@withLock
                check(directory.mkdirs() || directory.isDirectory)
                val metadata = File(directory, "product.json")
                val previousData = metadata.takeIf { it.isFile && it.length() <= 100_000 }?.let {
                    runCatching { Json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull()
                }
                val previous = previousData?.get("product") as? JsonObject
                val merged = AmazonProducts.withDetails(AmazonProducts.withDetails(media.product, previous ?: JsonObject(emptyMap())), media.product)
                val image = File(directory, "image")
                if (AmazonProducts.productImageUrl(previous ?: JsonObject(emptyMap())) != AmazonProducts.productImageUrl(merged)) image.delete()
                media.image?.takeIf { it.size <= 3 * 1_048_576 && AmazonProductImageProvider.validImage(it) }?.let { bytes ->
                    val pending = File(directory, "image.tmp")
                    pending.writeBytes(bytes)
                    check(pending.renameTo(image))
                }
                val json = buildJsonObject {
                    put("product", merged)
                    put("detailed", media.detailed || (previousData?.get("detailed") as? JsonPrimitive)?.booleanOrNull == true)
                }.toString()
                if (json.length <= 100_000) {
                    val pending = File(directory, "product.tmp")
                    pending.writeText(json)
                    check(pending.renameTo(metadata))
                }
            }
        }
    }

    suspend fun clearConversation(chatId: Int) = withContext(Dispatchers.IO) {
        val urls = lock.withLock {
            val directory = File(root, chatId.toString())
            val retained = directory.listFiles().orEmpty().mapNotNull { product ->
                runCatching {
                    val metadata = File(product, "product.json").takeIf { it.isFile && it.length() <= 100_000 } ?: return@runCatching null
                    val data = (Json.parseToJsonElement(metadata.readText()) as? JsonObject)?.get("product") as? JsonObject
                    data?.let(AmazonProducts::productImageUrl)
                }.getOrNull()
            }
            directory.deleteRecursively()
            retained
        }
        images.evict(urls)
    }

    private fun directory(chatId: Int, product: JsonObject): File? = key(product)?.let { File(File(root, chatId.toString()), it) }

    private fun key(product: JsonObject): String? {
        val domain = AmazonProducts.text(product, "marketplace") ?: return null
        val asin = AmazonProducts.text(product, "asin") ?: return null
        if (AmazonProducts.productUrl(domain, asin) == null) return null
        val identity = Json.encodeToString(kotlinx.serialization.serializer<List<String?>>(), listOf("marketplace", "asin", "variant", "seller", "condition").map { AmazonProducts.text(product, it) })
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
