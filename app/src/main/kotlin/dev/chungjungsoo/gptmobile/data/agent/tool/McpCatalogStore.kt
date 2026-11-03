package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted metadata only. Immutable chunks are written before the atomic manifest. */
@Singleton
class McpCatalogStore @Inject constructor(private val vault: SecretVault) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Manifest(val schema: Int = 1, val identity: String, val observedAt: Long, val chunks: List<String>, val digest: String)

    internal suspend fun load(uid: String, identity: String, now: Long): List<Tool>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val manifest = readManifest(reference(uid)) ?: return@withLock null
                if (manifest.schema != 1 || manifest.identity != identity || now - manifest.observedAt !in 0..MAX_AGE_MS || manifest.chunks.size !in 1..MAX_CHUNKS) return@withLock null
                val parts = mutableListOf<ByteArray>()
                try {
                    for (ref in manifest.chunks) {
                        require(ref.startsWith(CHUNK_PREFIX))
                        parts += vault.read(ref) ?: return@withLock null
                    }
                    require(parts.all { it.size <= CHUNK_BYTES })
                    val bytes = java.io.ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
                    try {
                        require(hash(bytes) == manifest.digest)
                        json.decodeFromString<List<Tool>>(bytes.decodeToString()).also(::validate)
                    } finally {
                        bytes.fill(0)
                    }
                } finally {
                    parts.forEach { it.fill(0) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Corrupt or unavailable metadata cannot disable live discovery.
                null
            }
        }
    }

    internal suspend fun save(uid: String, identity: String, tools: List<Tool>, now: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            validate(tools)
            val bytes = json.encodeToString(tools).encodeToByteArray()
            try {
                require(bytes.size <= CHUNK_BYTES * MAX_CHUNKS)
                val manifestRef = reference(uid)
                val old = readManifest(manifestRef)
                val digest = hash(bytes)
                val chunks = (0 until (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES).map { index ->
                    val ref = CHUNK_PREFIX + hash("$identity|$digest|$index".encodeToByteArray())
                    val chunk = bytes.copyOfRange(index * CHUNK_BYTES, minOf(bytes.size, (index + 1) * CHUNK_BYTES))
                    try {
                        vault.put(ref, chunk)
                    } finally {
                        chunk.fill(0)
                    }
                    ref
                }
                val manifest = json.encodeToString(Manifest(identity = identity, observedAt = now, chunks = chunks, digest = digest)).encodeToByteArray()
                try {
                    vault.put(manifestRef, manifest)
                } finally {
                    manifest.fill(0)
                }
                old?.chunks?.filter { it !in chunks }?.forEach { vault.delete(it) }
                trim(manifestRef)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private suspend fun trim(keep: String) {
        val manifestRefs = vault.references().filter { it.startsWith(MANIFEST_PREFIX) }
        val manifests = manifestRefs.mapNotNull { ref -> readManifest(ref)?.let { ref to it } }
        val validRefs = manifests.map { it.first }.toSet()
        manifestRefs.filter { it !in validRefs && it != keep }.forEach { vault.delete(it) }
        val retained = manifests.sortedByDescending { it.second.observedAt }.filter { it.first != keep }.take(MAX_CATALOGS - 1).map { it.first }.toSet() + keep
        manifests.filter { it.first !in retained }.forEach { vault.delete(it.first) }
        val referenced = manifests.filter { it.first in retained }.flatMap { it.second.chunks }.toSet()
        vault.references().filter { it.startsWith(CHUNK_PREFIX) && it !in referenced }.forEach { vault.delete(it) }
    }

    private suspend fun readManifest(ref: String): Manifest? {
        val bytes = vault.read(ref) ?: return null
        return try {
            json.decodeFromString<Manifest>(bytes.decodeToString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun reference(uid: String) = MANIFEST_PREFIX + hash(uid.encodeToByteArray())

    private fun validate(tools: List<Tool>) {
        require(tools.size <= 1000 && tools.map { it.name }.distinct().size == tools.size)
        require(tools.all { it.name.isNotBlank() && it.name.length <= 256 })
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_PREFIX = "mcp_catalog_m_"
        const val CHUNK_PREFIX = "mcp_catalog_c_"
        const val MAX_CATALOGS = 16
        const val CHUNK_BYTES = 48 * 1024
        const val MAX_CHUNKS = 86
        const val MAX_AGE_MS = 24 * 60 * 60_000L
    }
}
