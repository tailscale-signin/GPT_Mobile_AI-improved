package dev.chungjungsoo.gptmobile.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelImportResult
import dev.chungjungsoo.gptmobile.data.localmodel.ModelFolderArchive
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Persisted source grant + idempotent content identity allow a process restart to safely retry. */
@HiltWorker
class LocalModelImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LocalModelRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = inputData.getString(SOURCE)?.let(Uri::parse) ?: return@withContext Result.failure()
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.local_ai_import), NotificationManager.IMPORTANCE_LOW))
        val temporary = File(applicationContext.cacheDir, "import-$id.localmodel")
        try {
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(applicationContext.getString(R.string.local_ai_import))
                .setContentText(applicationContext.getString(R.string.local_ai_import_working))
                .setProgress(0, 0, true).setOngoing(true)
                .addAction(android.R.drawable.ic_delete, applicationContext.getString(R.string.cancel), androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
            setForeground(ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val resolver = applicationContext.contentResolver
            val coroutine = currentCoroutineContext()
            val check = {
                coroutine.ensureActive()
                check(!isStopped) { "Import stopped" }
            }
            val folder = inputData.getBoolean(FOLDER, false)
            if (folder) ModelFolderArchive.copy(resolver, uri, temporary, check)
            val name = if (folder) {
                "folder.localmodel"
            } else {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "unknown"
            }
            val input = if (folder) temporary.inputStream() else resolver.openInputStream(uri) ?: error("Source permission lost. Choose the source again.")
            var lastUpdate = 0L
            val result = repository.importPersisted(input, name, id.toString(), check) { bytes ->
                val now = System.nanoTime() / 1_000_000
                if (now - lastUpdate >= 500) {
                    lastUpdate = now
                    setProgressAsync(Data.Builder().putLong(BYTES, bytes).build())
                }
            }
            when (result) {
                is LocalModelImportResult.Success -> Result.success(Data.Builder().putString(MESSAGE, applicationContext.getString(R.string.local_ai_import_done)).build())
                is LocalModelImportResult.Failure -> Result.failure(Data.Builder().putString(MESSAGE, result.message.take(1000)).build())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(Data.Builder().putString(MESSAGE, failure.message?.take(1000) ?: "Import could not finish; choose the source again.").build())
        } finally {
            temporary.delete()
        }
    }
    companion object {
        const val WORK = "local-model-import"
        const val SOURCE = "source"
        const val FOLDER = "folder"
        const val BYTES = "bytes"
        const val MESSAGE = "message"
        private const val CHANNEL = "model-import"
    }
}
