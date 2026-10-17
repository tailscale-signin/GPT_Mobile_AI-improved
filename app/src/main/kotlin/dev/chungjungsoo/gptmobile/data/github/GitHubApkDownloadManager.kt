package dev.chungjungsoo.gptmobile.data.github

import android.app.DownloadManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.worker.GitHubApkDownloadWorker
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class GitHubApkDownloadStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class GitHubDownloadedApk(val fileName: String, val uri: Uri, val sizeBytes: Long)

data class GitHubApkDownloadState(
    val downloadKey: String,
    val connectionUid: String,
    val buildId: String,
    val title: String,
    val owner: String,
    val repo: String,
    val source: GitHubBuildSource,
    val status: GitHubApkDownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val files: List<GitHubDownloadedApk>,
    val error: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val releaseTag: String? = null,
    val createdAt: String? = null
)

/** WorkManager owns transfers; leaving the workspace never cancels a download. */
@Singleton
class GitHubApkDownloadManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)
    private val storeRevision = MutableStateFlow(0L)

    fun key(connectionUid: String, buildId: String): String = GitHubApkTransferPolicy.downloadKey(connectionUid, buildId)

    @Synchronized
    fun enqueue(connectionUid: String, build: GitHubApkBuild): String {
        require(!build.expired) { "This workflow artifact has expired. Choose a newer build." }
        GitHubApkTransferPolicy.requireRepository(build.owner, build.repo)
        require(build.assetId > 0 && build.sizeBytes in 0..GitHubApkTransferPolicy.MAX_TRANSFER_BYTES) { "This build exceeds the 2 GB download limit." }
        GitHubApkTransferPolicy.expectedSha256(build.digest)
        if (build.source == GitHubBuildSource.RELEASE) GitHubApkTransferPolicy.safeApkName(build.fileName)
        val downloadKey = key(connectionUid, build.id)
        val record = GitHubApkDownloadRecord(
            connectionUid, build.id, build.owner, build.repo, build.source, build.assetId,
            build.title.take(200), build.fileName.take(250), build.sizeBytes, build.digest,
            build.branch?.take(250), build.commitSha?.take(64), build.releaseTag?.take(200), build.createdAt.take(80)
        )
        GitHubApkDownloadStore.saveRecord(context, downloadKey, record)
        enqueueKey(downloadKey)
        return downloadKey
    }

    @Synchronized
    fun retry(downloadKey: String) {
        requireNotNull(GitHubApkDownloadStore.record(context, downloadKey)) { "This download is no longer available. Refresh builds." }
        enqueueKey(downloadKey)
    }

    fun cancel(downloadKey: String) {
        GitHubApkTransferPolicy.requireKey(downloadKey)
        workManager.cancelUniqueWork(workName(downloadKey))
    }

    suspend fun remove(downloadKey: String) = withContext(Dispatchers.IO) {
        GitHubApkTransferPolicy.requireKey(downloadKey)
        synchronized(this@GitHubApkDownloadManager) {
            check(workManager.getWorkInfosForUniqueWork(workName(downloadKey)).get().none { !it.state.isFinished }) {
                "Cancel this download before removing its local files."
            }
            val directory = GitHubApkDownloadStore.directory(context, downloadKey)
            if (directory.exists()) {
                RandomAccessFile(File(directory, "transfer.lock"), "rw").use { file ->
                    val lock = try {
                        file.channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    check(lock != null) { "The download is still stopping. Try removing it again in a moment." }
                    try {
                        check(directory.deleteRecursively()) { "Could not remove all local APK files. Try again." }
                    } finally {
                        lock.release()
                    }
                }
            }
            storeRevision.update { it + 1 }
        }
    }

    fun observe(): Flow<List<GitHubApkDownloadState>> = combine(workManager.getWorkInfosByTagFlow(WORK_TAG), storeRevision) { entries, _ ->
        val workByKey = entries.mapNotNull { info ->
            info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG)?.let { it to info }
        }.groupBy({ it.first }, { it.second })
        GitHubApkDownloadStore.records(context).map { (downloadKey, record) ->
            val info = workByKey[downloadKey]?.maxByOrNull { value -> value.tags.firstOrNull { it.startsWith(TIME_TAG) }?.removePrefix(TIME_TAG)?.toLongOrNull() ?: 0L }
            val files = if (info == null || info.state.isFinished) storedFiles(downloadKey) else emptyList()
            val status = when (info?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GitHubApkDownloadStatus.QUEUED
                WorkInfo.State.RUNNING -> GitHubApkDownloadStatus.RUNNING
                WorkInfo.State.SUCCEEDED -> if (files.isEmpty()) GitHubApkDownloadStatus.FAILED else GitHubApkDownloadStatus.SUCCEEDED
                WorkInfo.State.FAILED -> if (files.isEmpty()) GitHubApkDownloadStatus.FAILED else GitHubApkDownloadStatus.SUCCEEDED
                WorkInfo.State.CANCELLED, null -> if (files.isEmpty()) GitHubApkDownloadStatus.CANCELLED else GitHubApkDownloadStatus.SUCCEEDED
            }
            val data = if (info?.state?.isFinished == true) info.outputData else info?.progress
            val total = (data?.getLong(TOTAL_BYTES, record.sizeBytes) ?: record.sizeBytes).takeIf { it > 0 }
            GitHubApkDownloadState(
                downloadKey, record.connectionUid, record.buildId, record.title, record.owner, record.repo, record.source, status,
                data?.getLong(RECEIVED_BYTES, if (files.isEmpty()) 0L else total ?: 0L) ?: if (files.isEmpty()) 0L else total ?: 0L,
                total, files,
                if (info?.state == WorkInfo.State.SUCCEEDED && files.isEmpty()) "Downloaded files are no longer available. Download this build again." else info?.outputData?.getString(ERROR_MESSAGE),
                record.branch, record.commitSha, record.releaseTag, record.createdAt
            )
        }
    }.flowOn(Dispatchers.IO)

    fun completedFiles(state: GitHubApkDownloadState): List<GitHubDownloadedApk> = storedFiles(state.downloadKey)

    fun openDownloads(context: Context) {
        context.startActivity(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun share(context: Context, apk: GitHubDownloadedApk) {
        requireOwnUri(apk.uri)
        val intent = Intent(Intent.ACTION_SEND).setType(APK_MIME).putExtra(Intent.EXTRA_STREAM, apk.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(apk.fileName, apk.uri) }
        context.startActivity(Intent.createChooser(intent, "Share APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun saveToDownloads(apk: GitHubDownloadedApk): Uri = withContext(Dispatchers.IO) {
        requireOwnUri(apk.uri)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, GitHubApkTransferPolicy.safeApkName(apk.fileName))
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GPT Mobile")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw GitHubApkDownloadException("Could not create a file in Downloads.")
        try {
            val source = resolver.openInputStream(apk.uri) ?: throw GitHubApkDownloadException("The downloaded APK is no longer available.")
            source.use { input ->
                val target = resolver.openOutputStream(destination) ?: throw GitHubApkDownloadException("Could not open the Downloads file.")
                target.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count <= GitHubApkTransferPolicy.MAX_APK_BYTES - total) { "The APK exceeds the supported file size." }
                        output.write(buffer, 0, count)
                        total += count
                    }
                    check(total == apk.sizeBytes) { "The APK file changed before it could be saved." }
                }
            }
            check(resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) > 0) {
                "Could not finish saving the APK."
            }
            destination
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun requireOwnUri(uri: Uri) {
        require(uri.scheme == "content" && uri.authority == context.packageName + ".fileprovider") { "Invalid downloaded APK." }
    }

    private fun storedFiles(downloadKey: String): List<GitHubDownloadedApk> = GitHubApkDownloadStore.completed(context, downloadKey).map { file ->
        GitHubDownloadedApk(file.name, FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file), file.length())
    }

    private fun enqueueKey(downloadKey: String) {
        val request = OneTimeWorkRequestBuilder<GitHubApkDownloadWorker>()
            .setInputData(Data.Builder().putString(DOWNLOAD_KEY, downloadKey).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG).addTag(KEY_TAG + downloadKey).addTag(TIME_TAG + System.currentTimeMillis())
            .build()
        workManager.enqueueUniqueWork(workName(downloadKey), ExistingWorkPolicy.KEEP, request)
        storeRevision.update { it + 1 }
    }

    companion object {
        const val WORK_TAG = "github_apk_download"
        const val KEY_TAG = "github_apk_key:"
        const val TIME_TAG = "github_apk_time:"
        const val DOWNLOAD_KEY = "download_key"
        const val RECEIVED_BYTES = "received_bytes"
        const val TOTAL_BYTES = "total_bytes"
        const val ERROR_MESSAGE = "error_message"
        const val APK_MIME = "application/vnd.android.package-archive"
        fun workName(downloadKey: String): String = "$WORK_TAG:$downloadKey"
    }
}

@Serializable
internal data class GitHubApkDownloadRecord(
    val connectionUid: String,
    val buildId: String,
    val owner: String,
    val repo: String,
    val source: GitHubBuildSource,
    val assetId: Long,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val digest: String?,
    val branch: String? = null,
    val commitSha: String? = null,
    val releaseTag: String? = null,
    val createdAt: String? = null
)

@Serializable
internal data class GitHubStoredApk(val fileName: String, val sizeBytes: Long)

/** Small records contain identifiers only. Tokens and temporary URLs never reach disk or WorkManager. */
internal object GitHubApkDownloadStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun directory(context: Context, downloadKey: String): File {
        GitHubApkTransferPolicy.requireKey(downloadKey)
        return File(File(context.filesDir, "github-apks"), downloadKey)
    }

    @Synchronized
    fun saveRecord(context: Context, downloadKey: String, record: GitHubApkDownloadRecord) {
        val directory = directory(context, downloadKey)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the APK download directory." }
        val file = File(directory, "build.json")
        val previous = this.record(context, downloadKey)
        // One asset identity cannot be redirected to another repository by a later enqueue.
        check(previous == null || previous.connectionUid == record.connectionUid && previous.buildId == record.buildId && previous.owner == record.owner && previous.repo == record.repo && previous.source == record.source && previous.assetId == record.assetId) { "This download identity changed. Refresh the build list." }
        if (!file.exists()) atomicWrite(file, json.encodeToString(record))
    }

    fun record(context: Context, downloadKey: String): GitHubApkDownloadRecord? = runCatching {
        val file = File(directory(context, downloadKey), "build.json")
        require(file.length() in 1..8192)
        json.decodeFromString<GitHubApkDownloadRecord>(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            .also { require(GitHubApkTransferPolicy.downloadKey(it.connectionUid, it.buildId) == downloadKey) }
    }.getOrNull()

    fun records(context: Context): List<Pair<String, GitHubApkDownloadRecord>> = File(context.filesDir, "github-apks").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }.sortedByDescending(File::lastModified)
        .mapNotNull { directory -> record(context, directory.name)?.let { directory.name to it } }

    fun cooldown(context: Context, downloadKey: String): Long = runCatching {
        File(directory(context, downloadKey), "cooldown").takeIf { it.length() in 1..32 }?.readText()?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    fun setCooldown(context: Context, downloadKey: String, until: Long) = atomicWrite(File(directory(context, downloadKey), "cooldown"), until.toString())

    fun completed(context: Context, downloadKey: String): List<File> = runCatching {
        val directory = directory(context, downloadKey)
        val file = File(directory, "apks.json")
        require(file.length() in 1..8192)
        val manifest = json.decodeFromString<List<GitHubStoredApk>>(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        require(manifest.size in 1..GitHubApkTransferPolicy.MAX_APKS)
        val apks = File(directory, "apks")
        manifest.map { item ->
            require(item.fileName == GitHubApkTransferPolicy.safeApkName(item.fileName))
            File(apks, item.fileName).also { apk -> require(apk.isFile && apk.length() == item.sizeBytes && item.sizeBytes in 1..GitHubApkTransferPolicy.MAX_APK_BYTES) }
        }
    }.getOrDefault(emptyList())

    fun finish(context: Context, downloadKey: String, files: List<File>) {
        require(files.size in 1..GitHubApkTransferPolicy.MAX_APKS)
        atomicWrite(File(directory(context, downloadKey), "apks.json"), json.encodeToString(files.map { GitHubStoredApk(it.name, it.length()) }))
    }

    private fun atomicWrite(file: File, value: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
