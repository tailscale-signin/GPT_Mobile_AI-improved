package dev.chungjungsoo.gptmobile.data.crawl

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Authority travels only in transport metadata, never in model-visible tool arguments. */
internal object CrawlerCompanionGrant {
    fun recognizes(connection: ToolConnection): Boolean = connection.alias == "crawler_companion"
    fun reference(connection: ToolConnection): String = "crawler_grant_${crawlHash(connection.connectionUid + ":" + connection.endpointUrl).take(48)}"
    suspend fun metadata(vault: SecretVault, connection: ToolConnection, profileUid: String, message: MessageV2?, policy: String, persistent: Boolean, requestLimit: Int): JsonObject {
        val turn = requireNotNull(message) { "Crawler companion requires an accepted conversation turn" }
        require(turn.createdAt > 0) { "Crawler companion turn identity is unavailable" }
        val expires = turn.createdAt / 1000 + 86_400
        check(expires > System.currentTimeMillis() / 1000) { "Crawler grant expired; start a new authorized crawl" }
        val key = requireNotNull(vault.read(reference(connection))) { "Set the companion grant key in Crawlers settings" }
        try {
            require(key.size >= 32) { "Crawler grant key must contain at least 32 bytes" }
            val root = crawlHash("$profileUid:${turn.chatId}:${turn.id}:${turn.createdAt}")
            val payload = buildJsonObject {
                put("principal", profileUid)
                put("grant_id", crawlHash("$root:$policy:${connection.connectionUid}"))
                put("root_run_id", root)
                put("work_item_id", root)
                put("policy_fingerprint", policy)
                put("generation", 0)
                put("max_requests", requestLimit.coerceIn(1, 600))
                put("max_downloaded_bytes", 64L * 1024 * 1024)
                put("expires_at", expires)
                put("credential_scope", crawlHash(connection.connectionUid + ":" + connection.endpointUrl))
                put("ephemeral", !persistent)
            }
            return buildJsonObject { put("gptmobile/crawlerGrant", sign(payload, key)) }
        } finally {
            key.fill(0)
        }
    }
    fun sign(payload: JsonObject, key: ByteArray): JsonObject {
        // WorkGrant is flat; sorted JSON matches the companion's canonical_json byte for byte.
        val canonical = JsonObject(payload.toSortedMap()).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("payload", payload)
            put("signature", signature)
        }
    }
}
