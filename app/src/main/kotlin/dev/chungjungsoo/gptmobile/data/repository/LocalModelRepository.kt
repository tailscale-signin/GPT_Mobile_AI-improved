package dev.chungjungsoo.gptmobile.data.repository

import androidx.work.WorkInfo
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelImportResult
import java.io.InputStream
import kotlinx.coroutines.flow.Flow

interface LocalModelRepository {
    fun observeAll(): Flow<List<LocalModel>>
    fun observeWorkInfos(): Flow<List<WorkInfo>>
    suspend fun getById(catalogEntryId: String): LocalModel?
    suspend fun resolveDownloadedPath(catalogEntryId: String): String?
    suspend fun startDownload(entry: CatalogEntry)
    suspend fun cancelDownload(catalogEntryId: String)
    suspend fun deleteModel(catalogEntryId: String)
    suspend fun importCustomModel(inputStream: InputStream, fileName: String): LocalModelImportResult
    suspend fun importWithProgress(inputStream: InputStream, fileName: String, checkCurrent: () -> Unit, progress: (Long) -> Unit): LocalModelImportResult = importCustomModel(inputStream, fileName)
    suspend fun importPersisted(inputStream: InputStream, fileName: String, jobId: String, checkCurrent: () -> Unit, progress: (Long) -> Unit): LocalModelImportResult = importWithProgress(inputStream, fileName, checkCurrent, progress)
    suspend fun linkSource(source: android.net.Uri, folder: Boolean, checkCurrent: () -> Unit, progress: (Long) -> Unit): LocalModelImportResult = error("Model source linking is unavailable")
    suspend fun totalStorageUsed(): Long
    suspend fun reconcile()
    suspend fun awaitActiveDownloadScheduling() = Unit
    suspend fun diskPartialBytes(record: LocalModel): Long = 0L
}
