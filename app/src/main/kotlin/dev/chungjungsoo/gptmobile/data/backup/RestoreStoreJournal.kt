package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RestoreStoreSnapshot(
    val transactionId: String,
    val selection: CompleteBackupSelection,
    val preferences: Map<String, BackupValue>?,
    val shared: Map<String, Map<String, BackupValue>>?,
    val secrets: Map<String, String>?,
    val protection: BackupProtection?,
    val protectionPassword: String = ""
)

/** Encrypted write-ahead state. Publish its manifest only after every part is durable. */
internal class RestoreStoreJournal(private val vault: SecretVault) {
    @Serializable private data class Manifest(val parts: List<String>)
    private val json = Json { encodeDefaults = true }

    suspend fun write(snapshot: RestoreStoreSnapshot) {
        check(vault.read(MANIFEST) == null) { "An interrupted restore requires recovery first." }
        cleanupParts()
        val bytes = json.encodeToString(RestoreStoreSnapshot.serializer(), snapshot).encodeToByteArray()
        try {
            require(bytes.size <= 64 * 1024 * 1024) { "Restore recovery state exceeds its storage budget." }
            val parts = (bytes.indices step PART_BYTES).mapIndexed { index, offset ->
                val ref = "${PREFIX}${snapshot.transactionId}-$index"
                val part = bytes.copyOfRange(offset, minOf(offset + PART_BYTES, bytes.size))
                try {
                    vault.put(ref, part)
                } finally {
                    part.fill(0)
                }
                ref
            }
            val manifest = json.encodeToString(Manifest.serializer(), Manifest(parts)).encodeToByteArray()
            try {
                vault.put(MANIFEST, manifest)
            } finally {
                manifest.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun read(): RestoreStoreSnapshot? {
        val bytes = vault.read(MANIFEST) ?: return null
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
        require(manifest.parts.size in 1..2048 && manifest.parts.all { it.startsWith(PREFIX) && it != MANIFEST })
        val output = java.io.ByteArrayOutputStream()
        manifest.parts.forEach { reference ->
            val part = requireNotNull(vault.read(reference)) { "Restore recovery data is missing. App state is preserved pending recovery." }
            try {
                output.write(part)
            } finally {
                part.fill(0)
            }
        }
        val payload = output.toByteArray()
        return try {
            json.decodeFromString(RestoreStoreSnapshot.serializer(), payload.decodeToString())
        } finally {
            payload.fill(0)
        }
    }

    suspend fun clear() {
        // Unpublish first. A crash during cleanup cannot expose an incomplete journal.
        vault.delete(MANIFEST)
        cleanupParts()
    }

    private suspend fun cleanupParts() {
        vault.references().filter { it.startsWith(PREFIX) && it != MANIFEST }.forEach { vault.delete(it) }
    }

    companion object {
        const val PREFIX = "restore-store-"
        private const val MANIFEST = "restore-store-manifest-v1"
        private const val PART_BYTES = 48 * 1024
    }
}
