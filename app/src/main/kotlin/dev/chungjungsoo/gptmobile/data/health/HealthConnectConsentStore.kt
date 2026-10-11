package dev.chungjungsoo.gptmobile.data.health

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Device-local consent, deliberately absent from app backups and settings imports. No health records. */
@Singleton
class HealthConnectConsentStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "health-connect-ai-consent-v1"))
    private val mutable = MutableStateFlow(false)
    val allowed = mutable.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false

    suspend fun isAllowed(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                mutable.value = runCatching { file.readFully().toString(Charsets.UTF_8) == "explicit-ai-conversation-consent-v1" }.getOrDefault(false)
                loaded = true
            }
            mutable.value
        }
    }

    suspend fun setAllowed(value: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Revocation takes effect in memory even when storage is unavailable.
            if (!value) {
                mutable.value = false
                loaded = true
                file.delete()
                check(!file.baseFile.exists()) { "Could not persist health consent revocation." }
                return@withLock
            }
            val stream = file.startWrite()
            try {
                stream.write(if (value) "explicit-ai-conversation-consent-v1".toByteArray() else byteArrayOf())
                file.finishWrite(stream)
                mutable.value = value
                loaded = true
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}
