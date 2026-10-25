package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class GgufNegativeKind { DETERMINISTIC_UNSUPPORTED, TRANSIENT_INITIALIZATION, RESOURCE_PRESSURE, NATIVE_CRASH }

@Serializable
internal data class GgufNegativeVerdict(
    val identity: String,
    val kind: GgufNegativeKind,
    val reason: String,
    val observedAtMillis: Long,
    val expiresAtMillis: Long? = null,
    val availableBytesAtFailure: Long? = null,
    val schemaVersion: Int = 1
)

/** Unload cannot erase failures. Explicit isolated Verify grants exactly one attempt for one key. */
internal class GgufNegativeCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val verificationPermits = mutableSetOf<String>()

    @Synchronized fun allowOneVerification(identity: String) {
        validate(identity)
        verificationPermits += identity
    }

    @Synchronized fun beforeAttempt(
        identity: String,
        availableBytes: Long?
    ): GgufNegativeVerdict? {
        validate(identity)
        if (verificationPermits.remove(identity)) return null
        val value = read(identity) ?: return null
        if (value.expiresAtMillis?.let { now() >= it } == true) return null
        if (value.kind == GgufNegativeKind.RESOURCE_PRESSURE &&
            availableBytes != null &&
            value.availableBytesAtFailure != null &&
            availableBytes - value.availableBytesAtFailure >= 64L * 1024 * 1024
        ) {
            return null
        }
        return value
    }

    @Synchronized fun read(identity: String): GgufNegativeVerdict? {
        validate(identity)
        val file = File(directory, "$identity.json")
        if (!file.isFile || file.length() !in 1..16_384) return null
        return runCatching { Json.decodeFromString<GgufNegativeVerdict>(file.readText()) }.getOrNull()?.takeIf {
            it.identity == identity &&
                it.schemaVersion == 1
        }
    }

    @Synchronized fun remember(
        identity: String,
        kind: GgufNegativeKind,
        reason: String,
        availableBytes: Long? = null
    ) {
        validate(identity)
        val timestamp = now()
        val expires =
            when (kind) {
                GgufNegativeKind.TRANSIENT_INITIALIZATION -> timestamp + 5 * 60_000L
                GgufNegativeKind.RESOURCE_PRESSURE -> timestamp + 30_000L
                GgufNegativeKind.DETERMINISTIC_UNSUPPORTED, GgufNegativeKind.NATIVE_CRASH -> null
            }
        val value = GgufNegativeVerdict(identity, kind, reason.take(1024), timestamp, expires, availableBytes)
        check(directory.isDirectory || directory.mkdirs())
        val pending = File.createTempFile("verdict-", ".pending", directory)
        try {
            pending.outputStream().use { output ->
                output.write(Json.encodeToString(value).toByteArray())
                output.fd.sync()
            }
            java.nio.file.Files.move(
                pending.toPath(),
                File(directory, "$identity.json").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            pending.delete()
        }
    }

    @Synchronized fun success(identity: String) {
        validate(identity)
        File(directory, "$identity.json").delete()
    }

    private fun validate(identity: String) {
        require(identity.matches(Regex("[a-f0-9]{64}")))
    }

    companion object {
        fun identity(
            modelDigest: String,
            spec: LocalEngineSpec
        ): String =
            dev.chungjungsoo.gptmobile.data.research.researchHash(
                "negative-v1|$modelDigest|${dev.melo.gguf.GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|${dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT}|${spec.engineConfiguration()}"
            )

        fun category(failure: Throwable): GgufNegativeKind? =
            when (failure.ggufFailureCategory()) {
                GgufFailureCategory.CANCELLED -> null
                GgufFailureCategory.UNSUPPORTED_OPERATION, GgufFailureCategory.DEVICE_UNAVAILABLE -> GgufNegativeKind.DETERMINISTIC_UNSUPPORTED
                GgufFailureCategory.ALLOCATION -> GgufNegativeKind.RESOURCE_PRESSURE
                GgufFailureCategory.NATIVE_PROCESS_EXIT -> GgufNegativeKind.NATIVE_CRASH
                GgufFailureCategory.SHADER_COMPILATION, GgufFailureCategory.MODEL_LOAD -> GgufNegativeKind.TRANSIENT_INITIALIZATION
                GgufFailureCategory.UNKNOWN -> null
            }
    }
}
