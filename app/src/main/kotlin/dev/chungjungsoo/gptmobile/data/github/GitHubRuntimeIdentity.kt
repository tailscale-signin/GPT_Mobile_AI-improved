package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.BuildConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Process identity accompanies new receipts; restoring old evidence never supplies a new build identity. */
internal object GitHubRuntimeIdentity {
    fun current(): JsonObject = buildJsonObject {
        put("application_id", BuildConfig.APPLICATION_ID)
        put("version_name", BuildConfig.VERSION_NAME)
        put("version_code", BuildConfig.VERSION_CODE)
        put("build_type", BuildConfig.BUILD_TYPE)
        put("app_source_sha", BuildConfig.APP_COMMIT)
        put("identity_source", "RUNNING_PROCESS_BUILD_CONFIG")
        put(
            "device_environment",
            buildJsonObject {
                put("manufacturer", android.os.Build.MANUFACTURER.orEmpty())
                put("model", android.os.Build.MODEL.orEmpty())
                put("device", android.os.Build.DEVICE.orEmpty())
                put("sdk_int", android.os.Build.VERSION.SDK_INT)
                put("scope", "Runtime environment metadata; no device test execution implied")
            }
        )
        // Environment metadata identifies this process. It is not a device test result.
        put("device_test_status", "NOT_TESTED")
    }
}
