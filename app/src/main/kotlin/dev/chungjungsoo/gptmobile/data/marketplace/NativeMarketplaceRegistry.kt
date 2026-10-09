package dev.chungjungsoo.gptmobile.data.marketplace

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NativePluginInstallation(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val credentialRef: String? = null,
    val maxResults: Int = 10,
    val dailyLimit: Int = 50,
    val usageDay: String = "",
    val usageCount: Int = 0,
    val nextRequestAt: Long = 0,
    val endpoints: Map<String, String> = emptyMap(),
    val disabledOperations: Set<String> = emptySet()
) {
    fun ready(entry: GitHubMarketplacePackage) =
        (entry.provider != "openstreetmap" || endpoints.filterKeys { it !in disabledOperations }.values.any(NativeMarketplaceCatalog::validEndpoint)) &&
            (!NativeMarketplaceCatalog.requiresKey(entry) || credentialRef != null) &&
            (!NativeMarketplaceCatalog.requiresEndpoint(entry) || NativeMarketplaceCatalog.validEndpoint(endpoint))
}

data class NativeProviderConfiguration(val installation: NativePluginInstallation, val apiKey: String)

/** Install/enable state is atomic and persistent; credentials exist only in the Keystore vault. */
@Singleton
class NativeMarketplaceRegistry internal constructor(private val file: File, private val vault: SecretVault) {
    @Inject constructor(@ApplicationContext context: Context, vault: SecretVault) : this(File(context.noBackupFilesDir, "native-marketplace-v1.json"), vault)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val _state = MutableStateFlow<Map<String, NativePluginInstallation>>(emptyMap())
    val state = _state.asStateFlow()

    suspend fun load(): Map<String, NativePluginInstallation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            _state.value
        }
    }

    suspend fun install(entry: GitHubMarketplacePackage) = change(entry) { it ?: NativePluginInstallation() }

    suspend fun reload(): Map<String, NativePluginInstallation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded = false
            loadLocked()
            _state.value
        }
    }

    suspend fun backupState(): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            json.encodeToString(_state.value).encodeToByteArray()
        }
    }

    suspend fun uninstall(entry: GitHubMarketplacePackage) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        mutex.withLock {
            loadLocked()
            val previous = _state.value[entry.id]
            // Disable first and keep the reference if credential deletion fails, so uninstall can retry.
            if (previous != null) writeLocked(_state.value + (entry.id to previous.copy(enabled = false)))
            previous?.credentialRef?.let { vault.delete(it) }
            writeLocked(_state.value - entry.id)
        }
    }

    suspend fun setEnabled(entry: GitHubMarketplacePackage, enabled: Boolean) {
        if (enabled) configuration(entry, requireEnabled = false)
        change(entry) { current ->
            requireNotNull(current) { "Install this plugin first." }
            require(!enabled || current.ready(entry)) { "Complete the required fields first." }
            current.copy(enabled = enabled)
        }
    }

    suspend fun configure(entry: GitHubMarketplacePackage, endpoint: String, key: String, maxResults: Int, dailyLimit: Int, clearKey: Boolean = false, endpoints: Map<String, String> = emptyMap(), disabledOperations: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        require(entry.provider != "openstreetmap" || (endpoints.keys.all { it in setOf("geocode", "restrooms") } && endpoints.values.any(NativeMarketplaceCatalog::validEndpoint))) { "Configure at least one OpenStreetMap capability." }
        require(endpoints.values.all { it.isBlank() || NativeMarketplaceCatalog.validEndpoint(it) }) { "Enter valid managed/self-hosted HTTPS endpoints." }
        require(!NativeMarketplaceCatalog.requiresEndpoint(entry) || NativeMarketplaceCatalog.validEndpoint(endpoint)) { "Enter a managed/self-hosted HTTPS endpoint." }
        require(key.isBlank() || NativeMarketplaceCatalog.requiresKey(entry)) { "This provider does not use an API key." }
        require(key.isBlank() || NativeMarketplaceCatalog.validKey(key.trim())) { "Enter a valid API key." }
        mutex.withLock {
            loadLocked()
            val current = requireNotNull(_state.value[entry.id]) { "Install this plugin first." }
            val replacement = if (key.isNotBlank()) "marketplace_${UUID.randomUUID()}" else null
            if (replacement != null) {
                val bytes = key.trim().encodeToByteArray()
                try {
                    vault.put(replacement, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            val ref = replacement ?: if (clearKey) null else current.credentialRef
            val updated = current.copy(
                endpoint = if (NativeMarketplaceCatalog.requiresEndpoint(entry)) endpoint.trim() else "",
                credentialRef = ref,
                maxResults = maxResults.coerceIn(1, 10),
                dailyLimit = dailyLimit.coerceIn(1, 1000),
                endpoints = if (entry.provider == "openstreetmap") endpoints.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() } else emptyMap(),
                disabledOperations = if (entry.provider == "openstreetmap") disabledOperations.intersect(setOf("geocode", "restrooms")) else emptySet()
            )
            try {
                writeLocked(_state.value + (entry.id to updated.copy(enabled = current.enabled && updated.ready(entry))))
            } catch (error: Exception) {
                replacement?.let { vault.delete(it) }
                throw error
            }
            if (current.credentialRef != ref) current.credentialRef?.let { vault.delete(it) }
        }
    }

    suspend fun configuration(entry: GitHubMarketplacePackage, requireEnabled: Boolean = true): NativeProviderConfiguration {
        val record = load()[entry.id] ?: error("Install this plugin first.")
        check(!requireEnabled || record.enabled) { "This plugin is disabled." }
        check(record.ready(entry)) { "Complete the required plugin settings first." }
        val key = if (NativeMarketplaceCatalog.requiresKey(entry)) {
            val bytes = record.credentialRef?.let { vault.read(it) }
            if (bytes == null) {
                change(entry) { current ->
                    if (current?.credentialRef == record.credentialRef) current?.copy(enabled = false, credentialRef = null) else current
                }
                error("API key is missing. Add it in plugin settings.")
            }
            try {
                bytes.decodeToString()
            } finally {
                bytes.fill(0)
            }
        } else {
            ""
        }
        check(!NativeMarketplaceCatalog.requiresKey(entry) || NativeMarketplaceCatalog.validKey(key)) { "API key is missing or invalid." }
        return NativeProviderConfiguration(record, key)
    }

    suspend fun reserve(entry: GitHubMarketplacePackage, now: Long = System.currentTimeMillis()) = change(entry) { current ->
        requireNotNull(current) { "Install this plugin first." }
        check(current.enabled) { "This plugin is disabled." }
        check(current.ready(entry)) { "Complete required plugin settings first." }
        check(now >= current.nextRequestAt) { "Provider cooldown active. Try again shortly." }
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val used = if (current.usageDay == day) current.usageCount else 0
        check(used < current.dailyLimit) { "Daily request allowance reached. Adjust it in plugin settings or try tomorrow." }
        current.copy(usageDay = day, usageCount = used + 1, nextRequestAt = now + 1000)
    }

    suspend fun rateLimited(entry: GitHubMarketplacePackage) = change(entry) { it?.copy(nextRequestAt = System.currentTimeMillis() + 60_000) }

    private suspend fun change(entry: GitHubMarketplacePackage, transform: (NativePluginInstallation?) -> NativePluginInstallation?) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        mutex.withLock {
            loadLocked()
            val updated = transform(_state.value[entry.id])
            writeLocked(if (updated == null) _state.value - entry.id else _state.value + (entry.id to updated))
        }
    }

    private fun requireKnown(entry: GitHubMarketplacePackage) = require(GitHubMarketplaceCatalog.find(entry.id) == entry && NativeMarketplaceCatalog.supports(entry)) { "Unsupported Android plugin." }

    private fun loadLocked() {
        if (loaded) return
        val records = try {
            AtomicFile(file).openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 128 * 1024) { "Plugin registry exceeds its size limit." }
                    output.write(buffer, 0, count)
                }
                json.decodeFromString<Map<String, NativePluginInstallation>>(output.toByteArray().decodeToString())
            }
        } catch (_: FileNotFoundException) {
            emptyMap()
        }
        val bundled = bundleLegacyOpenStreetMap(records)
        _state.value = bundled.filterKeys { GitHubMarketplaceCatalog.find(it)?.let(NativeMarketplaceCatalog::supports) == true }.mapValues { (_, record) ->
            val safeRef = record.credentialRef?.takeIf { it.matches(Regex("marketplace_[a-f0-9-]{36}")) }
            record.copy(credentialRef = safeRef, enabled = record.enabled && safeRef == record.credentialRef, maxResults = record.maxResults.coerceIn(1, 10), dailyLimit = record.dailyLimit.coerceIn(1, 1000))
        }
        if (bundled != records) writeLocked(_state.value)
        loaded = true
    }

    internal fun bundleLegacyOpenStreetMap(records: Map<String, NativePluginInstallation>): Map<String, NativePluginInstallation> {
        val geocode = records["optional-nominatim"]
        val restrooms = records["optional-overpass"]
        if (geocode == null && restrooms == null) return records
        val existing = records["optional-openstreetmap"]
        val capabilities = buildMap {
            geocode?.endpoint?.takeIf(NativeMarketplaceCatalog::validEndpoint)?.let { put("geocode", it) }
            restrooms?.endpoint?.takeIf(NativeMarketplaceCatalog::validEndpoint)?.let { put("restrooms", it) }
            putAll(existing?.endpoints.orEmpty())
        }
        val source = existing ?: geocode ?: requireNotNull(restrooms)
        val previous = listOfNotNull(geocode, restrooms)
        val usageDay = existing?.usageDay ?: previous.maxOf { it.usageDay }
        val bundle = source.copy(
            endpoint = "",
            endpoints = capabilities,
            disabledOperations = existing?.disabledOperations ?: buildSet {
                if (geocode?.enabled != true) add("geocode")
                if (restrooms?.enabled != true) add("restrooms")
            },
            enabled = existing?.enabled ?: (geocode?.enabled == true || restrooms?.enabled == true),
            maxResults = existing?.maxResults ?: previous.minOf { it.maxResults },
            dailyLimit = existing?.dailyLimit ?: previous.minOf { it.dailyLimit },
            usageDay = usageDay,
            usageCount = existing?.usageCount ?: previous.filter { it.usageDay == usageDay }.sumOf { it.usageCount },
            nextRequestAt = listOfNotNull(existing, geocode, restrooms).maxOf { it.nextRequestAt }
        )
        return records - "optional-nominatim" - "optional-overpass" + ("optional-openstreetmap" to bundle)
    }

    private fun writeLocked(records: Map<String, NativePluginInstallation>) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(records).encodeToByteArray())
            atomic.finishWrite(output)
            _state.value = records
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
