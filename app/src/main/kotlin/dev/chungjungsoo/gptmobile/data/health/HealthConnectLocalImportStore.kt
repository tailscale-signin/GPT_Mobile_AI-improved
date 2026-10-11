package dev.chungjungsoo.gptmobile.data.health

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class HealthConnectLocalImport(
    val importedAt: String,
    val days: Int,
    val summary: JsonObject
)

internal object HealthConnectLocalImportCodec {
    private const val SCHEMA = "gptmobile.health.local-import.v1"
    const val MAX_BYTES = 1_000_000
    private val json = Json

    fun encode(value: HealthConnectLocalImport): ByteArray {
        require(value.days in 1..30)
        require(runCatching { java.time.Instant.parse(value.importedAt) }.isSuccess)
        require((value.summary["schema"] as? JsonPrimitive)?.content == HealthConnectTools.SCHEMA)
        val encoded = buildJsonObject {
            put("schema", SCHEMA)
            put("imported_at", value.importedAt)
            put("days", value.days)
            put("summary", value.summary)
        }.toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES) { "The local health snapshot exceeds the storage limit." }
        return encoded
    }

    fun decode(bytes: ByteArray): HealthConnectLocalImport? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return runCatching {
            val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root["schema"]?.jsonPrimitive?.contentOrNull == SCHEMA)
            val importedAt = root["imported_at"]?.jsonPrimitive?.contentOrNull ?: return null
            java.time.Instant.parse(importedAt)
            val days = root["days"]?.jsonPrimitive?.intOrNull ?: return null
            require(days in 1..30)
            val summary = root["summary"]?.jsonObject ?: return null
            require((summary["schema"] as? JsonPrimitive)?.content == HealthConnectTools.SCHEMA)
            HealthConnectLocalImport(importedAt, days, summary)
        }.getOrNull()
    }
}

/** Explicitly saved health snapshot in app-private storage excluded from Android backup. */
@Singleton
class HealthConnectLocalImportStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "health-connect-local-import-v1"))
    private val mutex = Mutex()

    suspend fun read(): HealthConnectLocalImport? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock null
            if (file.baseFile.length() !in 1..HealthConnectLocalImportCodec.MAX_BYTES.toLong()) {
                file.delete()
                return@withLock null
            }
            val result = runCatching { HealthConnectLocalImportCodec.decode(file.readFully()) }.getOrNull()
            if (result == null) file.delete()
            result
        }
    }

    suspend fun save(value: HealthConnectLocalImport) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = HealthConnectLocalImportCodec.encode(value)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            check(!file.baseFile.exists()) { "Could not delete the saved local health snapshot." }
        }
    }
}
