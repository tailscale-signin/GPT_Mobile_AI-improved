package dev.chungjungsoo.gptmobile.data.audit

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Capture once for the accepted run, never substitute today's APK/device when exporting history. */
@Singleton
class AppAuditEnvironmentCollector @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun capture(): AppAuditEnvironmentIdentity = withContext(Dispatchers.IO) {
        var observed = AppAuditEnvironmentIdentity(
            capturedAtEpochMs = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidApiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            process64Bit = Process.is64Bit()
        )
        try {
            val before = packageInfo()
            observed = observed.copy(versionCode = before.longVersionCode)
            val files = apkFiles(before)
            observed = observed.copy(installedApkCount = files.size)
            val result = withTimeoutOrNull(15_000) { hashAppAuditApks(files) }
                ?: return@withContext observed.copy(versionCode = before.longVersionCode, installedApkCount = files.size, apkIdentityFailure = AppAuditApkIdentityFailure.CAPTURE_TIMED_OUT)
            val after = packageInfo()
            if (before.longVersionCode != after.longVersionCode || before.lastUpdateTime != after.lastUpdateTime || files != apkFiles(after)) {
                return@withContext observed.copy(versionCode = before.longVersionCode, installedApkCount = files.size, apkIdentityFailure = AppAuditApkIdentityFailure.APK_CHANGED_DURING_CAPTURE)
            }
            observed.copy(
                versionCode = before.longVersionCode,
                baseApkSha256 = result.base,
                splitApkSha256 = result.splits,
                apkSetSha256 = result.set,
                installedApkCount = files.size
            )
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            observed.copy(
                apkIdentityFailure = when (failure) {
                    is AppAuditApkIdentityException -> failure.reason
                    is android.content.pm.PackageManager.NameNotFoundException -> AppAuditApkIdentityFailure.PACKAGE_UNAVAILABLE
                    else -> AppAuditApkIdentityFailure.APK_UNREADABLE
                }
            )
        }.let { checkNotNull(safeAppAuditEnvironment(it)) }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    private fun apkFiles(info: PackageInfo): List<File> {
        val application = info.applicationInfo ?: throw AppAuditApkIdentityException(AppAuditApkIdentityFailure.PACKAGE_UNAVAILABLE)
        val base = application.sourceDir?.takeIf(String::isNotBlank) ?: throw AppAuditApkIdentityException(AppAuditApkIdentityFailure.APK_UNREADABLE)
        return listOf(File(base)) + application.splitSourceDirs.orEmpty().sorted().map(::File)
    }
}
