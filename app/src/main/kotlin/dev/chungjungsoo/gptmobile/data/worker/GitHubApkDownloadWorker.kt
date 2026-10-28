package dev.chungjungsoo.gptmobile.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubApkArchive
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadException
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadManager
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadRecord
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadStore
import dev.chungjungsoo.gptmobile.data.github.GitHubApkTransferPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubBuildSource
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.channels.OverlappingFileLockException
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Downloads identifiers from GitHub, never model-provided URLs, and exposes only verified APK files. */
@HiltWorker
class GitHubApkDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val connections: ToolConnectionRepository,
    private val secretVault: SecretVault,
    private val gitHubPublicAuth: dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator? = null
) : CoroutineWorker(context, parameters) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val notificationId = id.hashCode()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        val key = inputData.getString(GitHubApkDownloadManager.DOWNLOAD_KEY)
            ?: return@withContext failure("The download request is missing. Choose a build again.")
        val record = GitHubApkDownloadStore.record(applicationContext, key)
            ?: return@withContext failure("The download request is no longer available. Refresh builds.")
        val directory = GitHubApkDownloadStore.directory(applicationContext, key)
        try {
            setForeground(foreground(record.title, 0, record.sizeBytes, "Downloading"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext failure("Android could not start this download. Open the app and retry.")
        }
        try {
            withTransferLock(directory) {
                val ready = GitHubApkDownloadStore.completed(applicationContext, key)
                if (ready.isNotEmpty()) {
                    return@withTransferLock Result.success(Data.Builder().putLong(GitHubApkDownloadManager.RECEIVED_BYTES, record.sizeBytes).build())
                }
                GitHubApkTransferPolicy.requireRepository(record.owner, record.repo)
                GitHubApkTransferPolicy.expectedSha256(record.digest)
                val cooldown = GitHubApkDownloadStore.cooldown(applicationContext, key)
                if (cooldown > System.currentTimeMillis()) throw rateLimited(cooldown)
                val token = currentToken(record)
                val part = File(directory, "download.part")
                if (part.length() > GitHubApkTransferPolicy.MAX_TRANSFER_BYTES) part.delete()
                val bytes = transfer(key, record, part, token)
                publishProgress(record.title, bytes, bytes, "Checking APKs")
                val coroutine = currentCoroutineContext()
                GitHubApkTransferPolicy.verifyDigest(part, record.digest) { coroutine.ensureActive() }
                // Staging is attempt-specific so a canceled worker cannot delete a later retry's files.
                val staging = File(directory, "staging-$id")
                directory.listFiles()?.filter { it.isDirectory && it.name.startsWith("staging-") }?.forEach(File::deleteRecursively)
                check(staging.mkdirs()) { "Could not create the APK extraction directory." }
                try {
                    val files = if (record.source == GitHubBuildSource.WORKFLOW) {
                        GitHubApkArchive.extract(part, staging, checkActive = { coroutine.ensureActive() }, validateApk = ::validateApk)
                    } else {
                        val target = File(staging, GitHubApkTransferPolicy.safeApkName(record.fileName))
                        check(part.renameTo(target)) { "Could not finish writing the APK." }
                        validateApk(target)
                        listOf(target)
                    }
                    coroutine.ensureActive()
                    val complete = File(directory, "apks")
                    if (complete.exists()) check(complete.deleteRecursively()) { "Could not replace the incomplete download." }
                    check(staging.renameTo(complete)) { "Could not finish the APK download." }
                    val committed = files.map { File(complete, it.name) }
                    GitHubApkDownloadStore.finish(applicationContext, key, committed)
                    part.delete()
                    Result.success(
                        Data.Builder().putLong(GitHubApkDownloadManager.RECEIVED_BYTES, bytes)
                            .putLong(GitHubApkDownloadManager.TOTAL_BYTES, bytes).build()
                    )
                } finally {
                    staging.deleteRecursively()
                }
            }
        } catch (cancelled: CancellationException) {
            // Retain only the private .part file for a user retry; it is never exposed as an APK.
            throw cancelled
        } catch (error: GitHubApkDownloadException) {
            if (error.retryable && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                if (!error.retryable && !error.retainPartial) File(directory, "download.part").delete()
                failure(error.message ?: "This build could not be downloaded.")
            }
        } catch (_: ZipException) {
            File(directory, "download.part").delete()
            failure("This build archive is incomplete or is not an Android APK. Download it again.")
        } catch (_: IOException) {
            // Transport failures may include signed URLs in exception text. Never persist or log them.
            if (runAttemptCount < MAX_RETRIES && directory.usableSpace > GitHubApkTransferPolicy.STORAGE_RESERVE_BYTES) {
                Result.retry()
            } else {
                failure("The download was interrupted. Check the connection and available storage, then retry.")
            }
        } catch (_: Exception) {
            File(directory, "download.part").delete()
            failure("This build could not be verified or saved. Check storage and choose an APK build.")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        val key = inputData.getString(GitHubApkDownloadManager.DOWNLOAD_KEY)
        val record = key?.let { GitHubApkDownloadStore.record(applicationContext, it) }
        return foreground(record?.title.orEmpty(), 0, record?.sizeBytes ?: 0, "Downloading")
    }

    private suspend fun currentToken(record: GitHubApkDownloadRecord): String {
        val connection = connections.getConnection(record.connectionUid)
            ?: throw GitHubApkDownloadException("This GitHub connection was removed. Reconnect before downloading.")
        if (connection.type != ToolConnectionType.GITHUB) throw GitHubApkDownloadException("Choose the native GitHub account for this download.")
        if (connection.secretRef != null) gitHubPublicAuth?.accessToken(connection)
        val bytes = connection.secretRef?.let { secretVault.read(it) }
        val token = try {
            bytes?.decodeToString()?.trim().orEmpty()
        } finally {
            bytes?.fill(0)
        }
        if ('\r' in token || '\n' in token) throw GitHubApkDownloadException("The GitHub credential is invalid. Reconnect the account.")
        if (record.source == GitHubBuildSource.WORKFLOW && token.isBlank()) {
            throw GitHubApkDownloadException("A GitHub token with Actions read access is needed to download workflow builds.")
        }
        return token
    }

    private suspend fun transfer(key: String, record: GitHubApkDownloadRecord, part: File, token: String): Long {
        val partialBytes = part.length()
        val initial = GitHubApkTransferPolicy.assetUri(record.owner, record.repo, record.assetId, record.source == GitHubBuildSource.WORKFLOW)
        val connection = openDownload(initial, token, partialBytes)
        try {
            val status = connection.responseCode
            if (status == 416) {
                part.delete()
                throw GitHubApkDownloadException("The previous partial download could not be resumed. Restarting.", retryable = true)
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                val limited = status == 429 ||
                    status == 403 &&
                    (connection.getHeaderField("X-RateLimit-Remaining") == "0" || !connection.getHeaderField("Retry-After").isNullOrBlank())
                if (limited) {
                    val until = GitHubApkTransferPolicy.retryDeadlineMillis(System.currentTimeMillis(), connection.getHeaderField("Retry-After"), connection.getHeaderField("X-RateLimit-Reset"))
                    GitHubApkDownloadStore.setCooldown(applicationContext, key, until)
                    throw rateLimited(until)
                }
                val retryable = status in 500..599
                val message = when (status) {
                    401, 403 -> "GitHub denied this download. Check repository access and Actions read permission for workflow builds."
                    404 -> "This build is no longer available, or this account cannot access it. Refresh builds."
                    410 -> "This workflow artifact has expired. Choose a newer build."
                    429 -> "GitHub is temporarily limiting downloads. Try again shortly."
                    else -> "GitHub could not deliver the build. Try again shortly."
                }
                throw GitHubApkDownloadException(message, retryable, retainPartial = status in setOf(401, 403))
            }
            val append = status == HttpURLConnection.HTTP_PARTIAL
            val declaredTotal = if (append) {
                GitHubApkTransferPolicy.resumedTotal(connection.getHeaderField("Content-Range"), partialBytes)
            } else {
                connection.contentLengthLong
            }
            if (declaredTotal > GitHubApkTransferPolicy.MAX_TRANSFER_BYTES) throw GitHubApkDownloadException("This build exceeds the 2 GB download limit.")
            val start = if (append) partialBytes else 0L
            val total = declaredTotal.takeIf { it > 0 } ?: record.sizeBytes
            if (total > start && part.parentFile!!.usableSpace < total - start + GitHubApkTransferPolicy.STORAGE_RESERVE_BYTES) {
                throw GitHubApkDownloadException("Not enough storage to download this build. Free space and retry.")
            }
            return coroutineScope {
                val running = async(Dispatchers.IO) {
                    var downloaded = start
                    var lastProgress = 0L
                    connection.inputStream.buffered().use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > GitHubApkTransferPolicy.MAX_TRANSFER_BYTES - downloaded || declaredTotal > 0 && count > declaredTotal - downloaded) {
                                    throw GitHubApkDownloadException("GitHub returned more build data than expected.")
                                }
                                output.write(buffer, 0, count)
                                downloaded += count
                                val now = System.currentTimeMillis()
                                if (now - lastProgress >= 1000) {
                                    if (part.parentFile!!.usableSpace < GitHubApkTransferPolicy.STORAGE_RESERVE_BYTES) throw GitHubApkDownloadException("Storage is nearly full. Free space and retry.")
                                    publishProgress(record.title, downloaded, total, "Downloading")
                                    lastProgress = now
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (downloaded <= 0 || declaredTotal > 0 && downloaded != declaredTotal) {
                        throw GitHubApkDownloadException("The build download was interrupted. Resuming shortly.", retryable = true)
                    }
                    downloaded
                }
                try {
                    running.await()
                } finally {
                    // Cancellation of await disconnects blocking I/O before the child is joined.
                    connection.disconnect()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun openDownload(initial: URI, token: String, partialBytes: Long): HttpURLConnection {
        var uri = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            GitHubApkTransferPolicy.requireTrustedUri(uri)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "GPT-Mobile-App")
            if (partialBytes > 0) connection.setRequestProperty("Range", "bytes=$partialBytes-")
            if (GitHubApkTransferPolicy.mayAuthorize(uri)) {
                connection.setRequestProperty("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location") ?: throw GitHubApkDownloadException("GitHub did not provide a download destination.")
                uri = GitHubApkTransferPolicy.redirect(uri, location)
                connection.disconnect()
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw GitHubApkDownloadException("GitHub redirected this download too many times.")
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File) {
        GitHubApkTransferPolicy.requireApkStructure(file)
        if (applicationContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName.isNullOrBlank()) {
            throw GitHubApkDownloadException("Android could not read this APK. Choose a valid Android build.")
        }
    }

    private suspend fun <T> withTransferLock(directory: File, block: suspend () -> T): T {
        RandomAccessFile(File(directory, "transfer.lock"), "rw").use { file ->
            file.channel.use { channel ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock != null) {
                        try {
                            return block()
                        } finally {
                            lock.release()
                        }
                    }
                    delay(250)
                }
            }
        }
    }

    private suspend fun publishProgress(title: String, received: Long, total: Long, phase: String) {
        setProgress(Data.Builder().putLong(GitHubApkDownloadManager.RECEIVED_BYTES, received).putLong(GitHubApkDownloadManager.TOTAL_BYTES, total).build())
        try {
            notifications.notify(notificationId, foreground(title, received, total, phase).notification)
        } catch (_: SecurityException) {
            // The foreground service can continue when notification permission is disabled.
        }
    }

    private fun foreground(title: String, received: Long, total: Long, phase: String): ForegroundInfo {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "GitHub APK downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val percent = if (total > 0) ((received.coerceAtMost(total) * 100) / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_gpt_mobile_monochrome_foreground)
            .setContentTitle(title.ifBlank { "GitHub APK download" }.take(120))
            .setContentText(if (total > 0) "$phase · $percent%" else phase)
            .setProgress(100, percent, total <= 0)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String): Result = Result.failure(Data.Builder().putString(GitHubApkDownloadManager.ERROR_MESSAGE, message.take(400)).build())

    private fun rateLimited(until: Long): GitHubApkDownloadException = GitHubApkDownloadException(
        "GitHub is limiting downloads. Retry after " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(until)) + ". Your partial download is retained.",
        retainPartial = true
    )

    private companion object {
        const val CHANNEL = "github_apk_downloads"
        const val MAX_RETRIES = 3
    }
}
