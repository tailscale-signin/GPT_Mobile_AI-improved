package dev.chungjungsoo.gptmobile.data.network

import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Device-local, non-exportable scope salt. The credential itself is never persisted here. */
internal object FreeQuotaIdentity {
    @Volatile private var key = ByteArray(32).also(SecureRandom()::nextBytes)

    @Synchronized fun initialize(file: File) {
        if (file.isFile) {
            val saved = file.readBytes()
            check(saved.size == 32) { "QUOTA_IDENTITY_UNAVAILABLE: stored device identity is invalid" }
            key = saved
        } else {
            file.parentFile?.mkdirs()
            val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
            val temporary = File(file.path + ".tmp")
            temporary.writeBytes(fresh)
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            key = fresh
        }
    }

    /** Accept the former SHA-256 fingerprint so persisted v1 scopes can migrate without the key. */
    fun protectDigest(digest: String): String {
        if (digest.startsWith("v2:")) return digest
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return "v2:" + mac.doFinal(digest.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
