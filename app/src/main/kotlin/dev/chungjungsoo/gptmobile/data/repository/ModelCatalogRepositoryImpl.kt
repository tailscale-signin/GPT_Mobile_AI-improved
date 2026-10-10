package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import android.util.AtomicFile
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.catalog.ModelCatalog
import dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelCatalogRepositoryImpl(
    private val fetchRemoteJson: suspend () -> String,
    private val readCacheJson: () -> String?,
    private val writeCacheJson: (String) -> Unit,
    private val readBundledJson: () -> String,
    private val appVersionName: String,
    private val deviceSocModel: String = "",
    private val readInstalledEntries: () -> List<CatalogEntry> = { emptyList() }
) : ModelCatalogRepository {

    // Fast in-memory cache to avoid redundant JSON parsing across screen navigations
    private val inMemoryCatalogCache = AtomicReference<ModelCatalog?>(null)

    constructor(
        context: Context,
        networkClient: NetworkClient,
        appVersionName: String
    ) : this(
        fetchRemoteJson = {
            networkClient().prepareGet(HOSTED_CATALOG_URL) {
                timeout {
                    requestTimeoutMillis = CATALOG_REQUEST_TIMEOUT_MS
                }
            }.execute { response ->
                check(response.status.isSuccess()) { "Model Catalog fetch failed: ${response.status}" }
                response.boundedMetadata()
            }
        },
        readCacheJson = {
            AtomicFile(cacheFile(context)).let { cache -> if (cache.baseFile.exists() || File(cache.baseFile.path + ".bak").exists()) cache.openRead().bufferedReader().use { it.readText() } else null }
        },
        writeCacheJson = { json ->
            val cache = AtomicFile(cacheFile(context))
            val output = cache.startWrite()
            try {
                output.write(json.toByteArray(Charsets.UTF_8))
                cache.finishWrite(output)
            } catch (error: Exception) {
                cache.failWrite(output)
                throw error
            }
        },
        readBundledJson = {
            context.assets.open(CATALOG_FILE_NAME).bufferedReader().use { it.readText() }
        },
        appVersionName = appVersionName,
        deviceSocModel = android.os.Build.SOC_MODEL.orEmpty(),
        readInstalledEntries = { dev.chungjungsoo.gptmobile.data.localmodel.LocalModelMetadata.read(context) }
    )

    override suspend fun getVisibleEntries(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        visibleEntries(
            remote = {
                fetchParsableCatalog(
                    source = { fetchRemoteJson() },
                    onParsed = writeCacheJson
                )
            },
            cached = {
                inMemoryCatalogCache.get() ?: fetchParsableCatalog(source = { readCacheJson() })
            },
            bundled = { fetchParsableCatalog(source = { readBundledJson() }) }
        )
    }

    override suspend fun getCachedVisibleEntries(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        visibleEntries(
            remote = { null },
            cached = {
                inMemoryCatalogCache.get() ?: fetchParsableCatalog(source = { readCacheJson() })
            },
            bundled = { fetchParsableCatalog(source = { readBundledJson() }) }
        )
    }

    private suspend fun visibleEntries(
        remote: suspend () -> ModelCatalog?,
        cached: suspend () -> ModelCatalog?,
        bundled: suspend () -> ModelCatalog?
    ): List<CatalogEntry> {
        val preferred = remote() ?: cached()
        val snapshot = bundled()
        // A branch/release may ship a newer catalogue than the hosted main branch.
        val catalog = if (snapshot != null && (preferred == null || snapshot.revision > preferred.revision)) {
            snapshot
        } else {
            preferred ?: snapshot ?: return emptyList()
        }
        inMemoryCatalogCache.set(catalog)
        val entries = ModelCatalogParser.visibleEntries(catalog, appVersionName)
        val deviceEntries = if (deviceSocModel.isBlank()) entries else dev.chungjungsoo.gptmobile.data.localmodel.LocalModelPackages.forDevice(entries, deviceSocModel)
        return (readInstalledEntries() + deviceEntries).associateBy { it.id }.values.toList()
    }

    private suspend fun fetchParsableCatalog(
        source: suspend () -> String?,
        onParsed: (String) -> Unit = {}
    ): ModelCatalog? {
        return try {
            val rawJson = source() ?: return null
            require(rawJson.length <= 2_000_000) { "Model catalog exceeds its size limit." }
            val catalog = ModelCatalogParser.parse(rawJson)
            require(catalog.models.map { it.id }.distinct().size == catalog.models.size) { "Duplicate model identities in catalog." }
            if (catalog.schemaVersion != ModelCatalogParser.SUPPORTED_SCHEMA_VERSION) {
                // An unsupported schema is not a usable source: fall through to the next
                // fallback instead of surfacing an empty catalog, and never cache it.
                return null
            }
            onParsed(rawJson)
            catalog
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val HOSTED_CATALOG_URL = "https://raw.githubusercontent.com/tailscale-signin/GPT_Mobile_AI-improved/main/model_catalog.json"
        const val CATALOG_FILE_NAME = "model_catalog.json"
        private const val CATALOG_REQUEST_TIMEOUT_MS = 15_000L

        private fun cacheFile(context: Context): File = File(context.filesDir, CATALOG_FILE_NAME)
    }
}
