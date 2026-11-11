package dev.chungjungsoo.gptmobile.data.audit

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class AppAuditApkIdentityFailure { PACKAGE_UNAVAILABLE, APK_UNREADABLE, APK_CHANGED_DURING_CAPTURE, APK_LIMIT_EXCEEDED, CAPTURE_TIMED_OUT, INCOMPLETE_IDENTITY }

/** Run-time observations only; no serial, device nickname, Android ID or installed file paths. */
@Serializable
data class AppAuditEnvironmentIdentity(
    val capturedAtEpochMs: Long,
    val versionCode: Long? = null,
    val baseApkSha256: String? = null,
    val splitApkSha256: List<String> = emptyList(),
    val apkSetSha256: String? = null,
    val installedApkCount: Int? = null,
    val apkIdentityFailure: AppAuditApkIdentityFailure? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val androidApiLevel: Int? = null,
    val supportedAbis: List<String> = emptyList(),
    val process64Bit: Boolean? = null
)

internal data class AppAuditApkDigests(val base: String, val splits: List<String>) {
    val set: String get() = appAuditApkSetDigest(base, splits)
}

internal class AppAuditApkIdentityException(val reason: AppAuditApkIdentityFailure) : IOException("Installed APK identity unavailable: ${reason.name}")

internal fun appAuditApkSetDigest(base: String, splits: List<String>): String =
    appAuditHash("installed-apk-set-v1\nbase:$base\n" + splits.sorted().joinToString("\n") { "split:$it" })

/** The base is first; split ordering is deliberately irrelevant, but no split may be omitted. */
internal suspend fun hashAppAuditApks(files: List<File>, maxTotalBytes: Long = 2L * 1024 * 1024 * 1024): AppAuditApkDigests {
    fun unavailable(reason: AppAuditApkIdentityFailure): Nothing = throw AppAuditApkIdentityException(reason)
    if (files.isEmpty() || files.size > 64 || files.map { it.absolutePath }.distinct().size != files.size) unavailable(AppAuditApkIdentityFailure.APK_LIMIT_EXCEEDED)
    var totalBytes = 0L
    val hashes = files.map { file ->
        currentCoroutineContext().ensureActive()
        val length = file.length()
        val modified = file.lastModified()
        if (!file.isFile || length <= 0) unavailable(AppAuditApkIdentityFailure.APK_UNREADABLE)
        if (length > maxTotalBytes - totalBytes) unavailable(AppAuditApkIdentityFailure.APK_LIMIT_EXCEEDED)
        val digest = MessageDigest.getInstance("SHA-256")
        var readBytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                readBytes += count
                totalBytes += count
                if (totalBytes > maxTotalBytes) unavailable(AppAuditApkIdentityFailure.APK_LIMIT_EXCEEDED)
                digest.update(buffer, 0, count)
            }
        }
        if (readBytes != length || file.length() != length || file.lastModified() != modified) unavailable(AppAuditApkIdentityFailure.APK_CHANGED_DURING_CAPTURE)
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    return AppAuditApkDigests(hashes.first(), hashes.drop(1).sorted())
}

internal fun safeAppAuditEnvironment(identity: AppAuditEnvironmentIdentity?): AppAuditEnvironmentIdentity? = identity?.let {
    fun digest(value: String?): String? = value?.takeIf { text -> text.matches(Regex("[a-f0-9]{64}")) }
    fun deviceLabel(value: String?): String? = value?.trim()?.takeIf { text -> text.length in 1..80 && text.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._()+-]*")) }
    val base = digest(it.baseApkSha256)
    val splits = it.splitApkSha256.take(64).mapNotNull(::digest).sorted()
    val complete = it.capturedAtEpochMs >= 0 &&
        it.apkIdentityFailure == null &&
        it.versionCode != null &&
        it.versionCode > 0 &&
        base != null &&
        it.installedApkCount in 1..64 &&
        it.installedApkCount == splits.size + 1 &&
        splits.size == it.splitApkSha256.size &&
        digest(it.apkSetSha256) == appAuditApkSetDigest(base, splits)
    it.copy(
        versionCode = it.versionCode?.takeIf { code -> code > 0 },
        baseApkSha256 = base.takeIf { complete },
        splitApkSha256 = splits.takeIf { complete }.orEmpty(),
        apkSetSha256 = it.apkSetSha256.takeIf { complete },
        installedApkCount = it.installedApkCount?.takeIf { count -> count in 1..64 },
        apkIdentityFailure = if (complete) null else it.apkIdentityFailure ?: AppAuditApkIdentityFailure.INCOMPLETE_IDENTITY,
        manufacturer = deviceLabel(it.manufacturer),
        model = deviceLabel(it.model),
        androidApiLevel = it.androidApiLevel?.takeIf { level -> level in 1..200 },
        supportedAbis = it.supportedAbis.take(8).filter { abi -> abi in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "riscv64") }.distinct()
    )
}

internal fun appAuditInstalledArtifactJson(identity: AppAuditEnvironmentIdentity?): JsonObject {
    val safe = safeAppAuditEnvironment(identity)
    return buildJsonObject {
        put("status", if (safe?.apkSetSha256 != null) "OBSERVED" else "UNAVAILABLE")
        put("reason", if (safe == null) "LEGACY_RUN_NO_ENVIRONMENT_SNAPSHOT" else safe.apkIdentityFailure?.name)
        put("capturedAtEpochMs", safe?.capturedAtEpochMs?.takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
        put("collectionMethod", if (safe == null) null else "ANDROID_PACKAGE_MANAGER_AND_FILE_SHA256")
        put("baseApkSha256", safe?.baseApkSha256)
        put("splitApkSha256", JsonArray(safe?.splitApkSha256.orEmpty().map(::JsonPrimitive)))
        put("installedApkCount", safe?.installedApkCount?.let(::JsonPrimitive) ?: JsonNull)
        put("apkSetSha256", safe?.apkSetSha256)
        put("sourceCommitIndependentlyVerified", false)
        put("meaning", "SHA-256 of all installed APK bytes at audit planning; a partial or failed capture grants no artifact identity. This observation does not verify the build-time source commit or establish device performance.")
    }
}

internal fun appAuditDeviceIdentityJson(identity: AppAuditEnvironmentIdentity?): JsonObject {
    val safe = safeAppAuditEnvironment(identity)
    val observed = safe != null && safe.capturedAtEpochMs >= 0 && safe.model != null && safe.androidApiLevel != null && safe.supportedAbis.isNotEmpty()
    return buildJsonObject {
        put("status", if (observed) "OBSERVED" else "UNAVAILABLE")
        put(
            "reason",
            if (safe == null) {
                "LEGACY_RUN_NO_ENVIRONMENT_SNAPSHOT"
            } else if (!observed) {
                "DEVICE_IDENTITY_INCOMPLETE"
            } else {
                null
            }
        )
        put("capturedAtEpochMs", safe?.capturedAtEpochMs?.takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
        put("collectionMethod", if (safe == null) null else "ANDROID_BUILD_PROPERTIES")
        put("manufacturer", safe?.manufacturer)
        put("model", safe?.model)
        put("androidApiLevel", safe?.androidApiLevel?.let(::JsonPrimitive) ?: JsonNull)
        put("supportedAbis", JsonArray(safe?.supportedAbis.orEmpty().map(::JsonPrimitive)))
        put("process64Bit", safe?.process64Bit?.let(::JsonPrimitive) ?: JsonNull)
        put("physicalDeviceVerified", false)
        put("meaning", "Observed platform properties identify the reported model and ABI support, not a unique handset or independently attested physical device. Serial numbers, user-assigned device names, Android IDs, fingerprints and paths are excluded.")
    }
}
