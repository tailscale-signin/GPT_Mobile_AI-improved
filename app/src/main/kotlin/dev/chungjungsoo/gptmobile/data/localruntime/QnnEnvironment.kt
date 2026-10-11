package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/** Configures the Qualcomm dispatch/HTP libraries before LiteRT-LM opens them. */
object QnnEnvironment {
    private const val TAG = "QnnEnvironment"

    @Volatile
    private var lastProbeStatus: QnnProbeStatus? = null

    @Volatile
    private var lastProbeKey: String? = null

    data class QnnProbeStatus(
        val isQualcommDevice: Boolean,
        val socModel: String,
        val processAbi: String,
        val nativeLibDir: String,
        val dispatchDir: String,
        val adspPath: String,
        val ldPath: String,
        val missingLibraries: List<String>,
        val skelFileExists: Boolean,
        val skelFilePath: String,
        // Prerequisites only; successful native initialization is the execution check.
        val isReady: Boolean,
        val errorMessage: String? = null,
        val libraryDigestStatus: String = "NOT_CHECKED",
        val libraryInventory: List<String> = emptyList()
    ) {
        /** Static package prerequisites only; this does not claim QNN has been verified. */
        val prerequisitesReady: Boolean get() = isReady
    }

    @Synchronized
    fun initialize(context: Context, forceRefresh: Boolean = false): QnnProbeStatus {
        val app = context.applicationContext
        val probeKey = probeKey(app)
        if (!forceRefresh && lastProbeKey == probeKey) {
            lastProbeStatus?.let { return it }
        }
        val soc = Build.SOC_MODEL.orEmpty()
        val nativeDir = File(app.applicationInfo.nativeLibraryDir)
        val required = QualcommSocSupport.requiredLibraries(soc)
        val qualcomm = isQualcommPlatform()
        val processIsArm64 = android.os.Process.is64Bit() && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val processAbi = if (android.os.Process.is64Bit()) "arm64-v8a" else Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val dispatch = if (qualcomm && processIsArm64 && required.isNotEmpty()) {
            ensurePhysicalLibraries(app, required)
        } else {
            nativeDir
        }
        val missing = required.filterNot { File(dispatch, it).isUsableLibrary() }
        val htp = QualcommSocSupport.htpVersion(soc)
        val skel = htp?.let { File(dispatch, "libQnnHtpV${it}Skel.so") }
        val adsp = listOf(
            dispatch.path,
            nativeDir.path,
            "/vendor/lib/rfsa/adsp",
            "/system/lib/rfsa/adsp",
            "/vendor/dsp/cdsp",
            "/dsp"
        ).plus(runCatching { Os.getenv("ADSP_LIBRARY_PATH") }.getOrNull().orEmpty().split(';'))
            .filter(String::isNotBlank).distinct().joinToString(";")
        val ld = listOf(dispatch.path, nativeDir.path)
            .plus(runCatching { Os.getenv("LD_LIBRARY_PATH") }.getOrNull().orEmpty().split(':'))
            .filter(String::isNotBlank).distinct().joinToString(":")
        var environmentError: String? = null
        if (qualcomm && processIsArm64 && required.isNotEmpty()) {
            try {
                Os.setenv("ADSP_LIBRARY_PATH", adsp, true)
                Os.setenv("LD_LIBRARY_PATH", ld, true)
            } catch (error: Exception) {
                environmentError = "Could not configure Qualcomm library paths: ${error.message}"
            }
        }
        val reason = when {
            !qualcomm -> "This device is not a Qualcomm platform"
            !processIsArm64 -> "Qualcomm NPU requires an arm64 process"
            required.isEmpty() -> "No packaged Qualcomm NPU support for SoC $soc"
            missing.isNotEmpty() -> "Missing Qualcomm libraries: ${missing.joinToString()}"
            environmentError != null -> environmentError
            else -> null
        }
        return QnnProbeStatus(
            isQualcommDevice = qualcomm, socModel = soc, nativeLibDir = nativeDir.path,
            processAbi = processAbi,
            dispatchDir = dispatch.path, adspPath = adsp, ldPath = ld, missingLibraries = missing,
            skelFileExists = skel?.isUsableLibrary() == true,
            skelFilePath = skel?.takeIf { it.isUsableLibrary() }?.path.orEmpty(),
            isReady = reason == null, errorMessage = reason
        ).also {
            lastProbeStatus = it
            lastProbeKey = probeKey
        }
    }

    /**
     * Re-validates the packaged dispatch stack immediately before native engine
     * creation. This prevents a stale successful probe from surviving an app,
     * split-APK, ABI or QAIRT deployment change.
     */
    @Synchronized
    fun prepareForExecution(context: Context): QnnProbeStatus {
        val probe = initialize(context, forceRefresh = true)
        if (!probe.isReady) return probe
        val directory = File(probe.dispatchDir)
        val required = QualcommSocSupport.requiredLibraries(probe.socModel)
        val key = probeKey(context) + required.joinToString { name -> File(directory, name).let { "$name:${it.length()}:${it.lastModified()}" } }
        if (verifiedKey == key) return (verifiedProbe ?: probe).also { lastProbeStatus = it }
        val expected = org.json.JSONObject(context.assets.open("local-runtime-native-libraries.json").bufferedReader().use { it.readText() }).getJSONObject("libraries")
        var valid = true
        val inventory = required.map { name ->
            val file = File(directory, name)
            val pinned = expected.optJSONObject("lib/arm64-v8a/$name")
            val digest = dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.sha256(file)
            val matches = pinned != null && pinned.optString("sha256").equals(digest, true) && pinned.optLong("bytes") == file.length()
            valid = valid && matches
            "$name:${file.length()}:$digest:${if (matches) "PASS" else "FAIL"}"
        }
        return probe.copy(
            isReady = valid,
            errorMessage = if (valid) null else "Packaged Qualcomm library checksum mismatch; native initialization blocked",
            libraryDigestStatus = if (valid) "TRUSTED_HASH_PASS" else "TRUSTED_HASH_FAIL",
            libraryInventory = inventory
        ).also {
            lastProbeStatus = it
            verifiedKey = if (valid) key else null
            verifiedProbe = it
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("QNN", "QNN_LIBRARY_INVENTORY · soc=${probe.socModel} · abi=${probe.processAbi} · directory=${if (probe.dispatchDir == probe.nativeLibDir) "installed" else "extracted"} · hashes=${it.libraryDigestStatus} · libraries=${inventory.joinToString(";")} · executionQualified=false")
        }
    }

    private var verifiedKey: String? = null
    private var verifiedProbe: QnnProbeStatus? = null

    @Synchronized
    fun resetProbe() {
        lastProbeStatus = null
        lastProbeKey = null
        verifiedKey = null
        verifiedProbe = null
    }

    fun getDispatchDir(context: Context): String = getProbeStatus(context).dispatchDir
    fun isEnvironmentConfigured(): Boolean = lastProbeStatus != null
    fun getProbeStatus(context: Context): QnnProbeStatus = initialize(context)
    fun verifyQnnLibraries(context: Context): Boolean = getProbeStatus(context).isReady

    private fun probeKey(context: Context): String {
        val install = context.packageManager.getPackageInfo(context.packageName, 0)
        return listOf(
            install.longVersionCode,
            install.lastUpdateTime,
            Build.SOC_MODEL.orEmpty(),
            Build.SOC_MANUFACTURER.orEmpty(),
            Build.FINGERPRINT.orEmpty(),
            context.applicationInfo.splitSourceDirs.orEmpty().joinToString(),
            Build.SUPPORTED_ABIS.joinToString(","),
            context.applicationInfo.nativeLibraryDir
        ).joinToString("|")
    }

    private fun ensurePhysicalLibraries(context: Context, required: List<String>): File {
        val info = context.applicationInfo
        val nativeDir = File(info.nativeLibraryDir)
        if (required.all { File(nativeDir, it).isUsableLibrary() }) return nativeDir
        // App updates can replace a library without changing its length. Scope extracted
        // files to the installed version rather than reusing files by size alone.
        val install = context.packageManager.getPackageInfo(context.packageName, 0)
        val target = File(context.noBackupFilesDir, "qnn_dispatch/${install.longVersionCode}-${install.lastUpdateTime}")
        target.mkdirs()
        val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        required.forEach { name ->
            val output = File(target, name)
            if (output.isUsableLibrary()) return@forEach
            val temporary = File(target, "$name.tmp")
            try {
                val source = File(nativeDir, name)
                if (source.isUsableLibrary()) {
                    source.copyTo(temporary, overwrite = true)
                } else {
                    for (apk in apks) {
                        ZipFile(apk).use { zip ->
                            val entry = zip.getEntry("lib/arm64-v8a/$name") ?: return@use
                            zip.getInputStream(entry).use { input -> temporary.outputStream().use(input::copyTo) }
                        }
                        if (temporary.isUsableLibrary()) break
                    }
                }
                if (temporary.isUsableLibrary()) {
                    check(temporary.renameTo(output)) { "Could not publish $name" }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not extract $name", error)
            } finally {
                temporary.delete()
            }
        }
        return target
    }

    internal fun File.isUsableLibrary(): Boolean = isFile && canRead() && length() > 0L

    fun isQualcommPlatform(): Boolean =
        Build.SOC_MANUFACTURER.orEmpty().let { it.contains("qualcomm", true) || it.equals("qti", true) } ||
            Build.HARDWARE.orEmpty().let { it.contains("qcom", true) || it.contains("qualcomm", true) } ||
            QualcommSocSupport.htpVersion(Build.SOC_MODEL.orEmpty()) != null
}
