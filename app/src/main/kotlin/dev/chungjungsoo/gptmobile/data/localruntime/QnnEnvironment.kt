package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Qualcomm QNN (Qualcomm Neural Network) and Hexagon DSP runtime environment configurator.
 *
 * Handles:
 * 1. Pre-seeding `ADSP_LIBRARY_PATH` (semicolon-separated for DSP FastRPC) and `LD_LIBRARY_PATH`
 *    before any QNN or LiteRT shared library is dlopened.
 * 2. Ensuring required Hexagon skeleton (`libQnnHtpV79Skel.so`) and dispatch shared libraries
 *    are physically extracted to internal storage if the device/APK packaging left them unextracted.
 * 3. Probing QNN native library and driver readiness.
 */
object QnnEnvironment {
    private const val TAG = "QnnEnvironment"

    private const val QNN_DISPATCH_DIR = "qnn_dispatch"

    val REQUIRED_QNN_LIBS = listOf(
        "libLiteRtCompilerPlugin_Qualcomm.so",
        "libLiteRtDispatch_Qualcomm.so",
        "libQnnHtp.so",
        "libQnnHtpV79CalculatorStub.so",
        "libQnnHtpV79Skel.so",
        "libQnnHtpV79Stub.so",
        "libQnnIr.so",
        "libQnnSaver.so",
        "libQnnSystem.so"
    )

    @Volatile
    private var isConfigured = false

    @Volatile
    private var dispatchDir: String = ""

    @Volatile
    private var lastProbeStatus: QnnProbeStatus? = null

    data class QnnProbeStatus(
        val isQualcommDevice: Boolean,
        val socModel: String,
        val nativeLibDir: String,
        val dispatchDir: String,
        val adspPath: String,
        val ldPath: String,
        val missingLibraries: List<String>,
        val skelFileExists: Boolean,
        val skelFilePath: String,
        val isReady: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Configures the QNN environment variables (`ADSP_LIBRARY_PATH` & `LD_LIBRARY_PATH`).
     * MUST be called as early as possible (e.g. in [Application.onCreate]) before any native
     * library loading occurs.
     */
    @Synchronized
    fun initialize(context: Context): QnnProbeStatus {
        val appContext = context.applicationContext
        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir

        // Determine or extract directory containing physical QNN libraries
        val resolvedDispatchDir = ensurePhysicalLibraries(appContext)
        dispatchDir = resolvedDispatchDir

        val adspSearchPaths = listOf(
            resolvedDispatchDir,
            nativeLibDir,
            "/vendor/lib64/rfs/dsp/snap",
            "/vendor/lib64/hw/audio",
            "/vendor/dsp/cdsp",
            "/vendor/lib64/snap",
            "/vendor/lib/rfsa/adsp",
            "/system/lib/rfsa/adsp",
            "/system/vendor/lib/rfsa/adsp",
            "/dsp"
        ).distinct()

        val adspEnvValue = adspSearchPaths.joinToString(";") // Semicolon delimiter for DSP FastRPC

        val ldSearchPaths = listOf(
            resolvedDispatchDir,
            nativeLibDir,
            "/vendor/lib64",
            "/system/lib64"
        ).distinct()

        val ldEnvValue = ldSearchPaths.joinToString(":") // Colon delimiter for Linux linker

        try {
            Os.setenv("ADSP_LIBRARY_PATH", adspEnvValue, true)
            Log.d(TAG, "Configured ADSP_LIBRARY_PATH=$adspEnvValue")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to setenv ADSP_LIBRARY_PATH: ${t.message}")
        }

        try {
            val currentLd = runCatching { Os.getenv("LD_LIBRARY_PATH") }.getOrNull()
            val finalLd = if (!currentLd.isNullOrBlank()) "$ldEnvValue:$currentLd" else ldEnvValue
            Os.setenv("LD_LIBRARY_PATH", finalLd, true)
            Log.d(TAG, "Configured LD_LIBRARY_PATH=$finalLd")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to setenv LD_LIBRARY_PATH: ${t.message}")
        }

        isConfigured = true

        val probe = probeEnvironment(appContext, resolvedDispatchDir, adspEnvValue, ldEnvValue)
        lastProbeStatus = probe
        return probe
    }

    fun getDispatchDir(context: Context): String {
        if (!isConfigured) {
            initialize(context)
        }
        return dispatchDir.ifBlank { context.applicationInfo.nativeLibraryDir }
    }

    fun isEnvironmentConfigured(): Boolean = isConfigured

    fun getProbeStatus(context: Context): QnnProbeStatus {
        return lastProbeStatus ?: initialize(context)
    }

    /**
     * Checks if physical `.so` files are available in [nativeLibDir]. If some are missing
     * (e.g. `extractNativeLibs = false` on Android 10+), extracts them from the APK's
     * `lib/arm64-v8a` into [context.filesDir]/qnn_dispatch.
     */
    private fun ensurePhysicalLibraries(context: Context): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val skelInNative = File(nativeDir, "libQnnHtpV79Skel.so")

        if (skelInNative.exists() && skelInNative.length() > 0L) {
            Log.d(TAG, "All QNN libraries physically present in nativeLibraryDir: ${nativeDir.absolutePath}")
            return nativeDir.absolutePath
        }

        // FastRPC requires libQnnHtpV79Skel.so on the physical filesystem. Extract from APK if needed.
        val targetDir = File(context.noBackupFilesDir, QNN_DISPATCH_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        runCatching {
            val apkPath = context.applicationInfo.sourceDir
            ZipFile(File(apkPath)).use { zip ->
                for (libName in REQUIRED_QNN_LIBS) {
                    val targetFile = File(targetDir, libName)
                    val sourceInNative = File(nativeDir, libName)

                    if (sourceInNative.exists() && sourceInNative.length() > 0L) {
                        if (!targetFile.exists() || targetFile.length() != sourceInNative.length()) {
                            sourceInNative.copyTo(targetFile, overwrite = true)
                        }
                    } else {
                        val entry = zip.getEntry("lib/arm64-v8a/$libName")
                        if (entry != null && (!targetFile.exists() || targetFile.length() != entry.size)) {
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            targetFile.setReadable(true, false)
                            targetFile.setExecutable(true, false)
                            Log.i(TAG, "Extracted $libName to ${targetFile.absolutePath}")
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed extracting QNN native libraries from APK: ${error.message}")
        }

        val skelInTarget = File(targetDir, "libQnnHtpV79Skel.so")
        return if (skelInTarget.exists() && skelInTarget.length() > 0L) {
            targetDir.absolutePath
        } else {
            nativeDir.absolutePath
        }
    }

    private fun probeEnvironment(
        context: Context,
        dispatchPath: String,
        adspPath: String,
        ldPath: String
    ): QnnProbeStatus {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val dispatchDirFile = File(dispatchPath)

        val missing = mutableListOf<String>()
        var skelFoundPath = ""

        for (lib in REQUIRED_QNN_LIBS) {
            val inDispatch = File(dispatchDirFile, lib)
            val inNative = File(nativeDir, lib)
            val exists = (inDispatch.exists() && inDispatch.length() > 0L) ||
                (inNative.exists() && inNative.length() > 0L)
            if (!exists) {
                missing.add(lib)
            }
            if (lib == "libQnnHtpV79Skel.so") {
                if (inDispatch.exists()) {
                    skelFoundPath = inDispatch.absolutePath
                } else if (inNative.exists()) {
                    skelFoundPath = inNative.absolutePath
                }
            }
        }

        val isQualcomm = isQualcommPlatform()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            Build.HARDWARE.orEmpty()
        }

        val ready = skelFoundPath.isNotBlank() && File(dispatchDirFile, "libLiteRtDispatch_Qualcomm.so").exists() ||
            File(nativeDir, "libLiteRtDispatch_Qualcomm.so").exists()

        return QnnProbeStatus(
            isQualcommDevice = isQualcomm,
            socModel = socModel,
            nativeLibDir = nativeDir.absolutePath,
            dispatchDir = dispatchPath,
            adspPath = adspPath,
            ldPath = ldPath,
            missingLibraries = missing,
            skelFileExists = skelFoundPath.isNotBlank(),
            skelFilePath = skelFoundPath,
            isReady = ready,
            errorMessage = if (!ready) "QNN libraries or HTP skeleton missing from filesystem" else null
        )
    }

    fun isQualcommPlatform(): Boolean {
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.orEmpty()
        } else {
            ""
        }
        val hardware = Build.HARDWARE.orEmpty()
        val board = Build.BOARD.orEmpty()
        return manufacturer.contains("qualcomm", ignoreCase = true) ||
            hardware.contains("qcom", ignoreCase = true) ||
            hardware.contains("qualcomm", ignoreCase = true) ||
            board.contains("qcom", ignoreCase = true)
    }
    
    /**
     * Verifies that QNN libraries are properly loaded and available for use
     */
    fun verifyQnnLibraries(context: Context): Boolean {
        try {
            val probe = getProbeStatus(context)
            val librariesAvailable = probe.isReady && probe.skelFileExists
            Log.d(TAG, "QNN libraries verification: ${if (librariesAvailable) "PASSED" else "FAILED"}")
            return librariesAvailable
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying QNN libraries: ${e.message}", e)
            return false
        }
    }
}