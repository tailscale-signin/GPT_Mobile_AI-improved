package dev.chungjungsoo.gptmobile.data.permissions

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-local receipts are deliberately outside application backups and profile cloning. */
@Singleton
class ProviderActivationStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    @Serializable
    private data class Receipt(val scope: String, val acceptedAt: Long, val origin: String = "USER")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir, "provider-activation-v1.json")) }

    suspend fun required(profile: PlatformV2): Boolean {
        val policy = ProviderPrivacyPolicy.forProfile(profile) ?: return false
        if (policy.provider.isRetired) return true
        if (policy.enabledByDefault) return false
        return withContext(Dispatchers.IO) { lock.withLock { read()[profile.uid]?.scope != ProviderPrivacyPolicy.scope(profile) } }
    }

    suspend fun accept(profile: PlatformV2) = mutate { receipts ->
        check(ProviderPrivacyPolicy.forProfile(profile)?.provider?.isRetired != true) { "Provider removed." }
        receipts + (profile.uid to Receipt(ProviderPrivacyPolicy.scope(profile), System.currentTimeMillis()))
    }

    suspend fun revoke(uid: String) = mutate { it - uid }

    suspend fun clear() = mutate { emptyMap() }

    private fun read(): Map<String, Receipt> = runCatching {
        file.openRead().use { json.decodeFromString<Map<String, Receipt>>(it.bufferedReader().readText()) }
    }.getOrDefault(emptyMap())

    private suspend fun mutate(change: (Map<String, Receipt>) -> Map<String, Receipt>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val data = json.encodeToString(change(read())).toByteArray()
            val stream = file.startWrite()
            try {
                stream.write(data)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}
