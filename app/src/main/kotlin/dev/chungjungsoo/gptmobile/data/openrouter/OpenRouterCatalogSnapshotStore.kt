package dev.chungjungsoo.gptmobile.data.openrouter

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Public capability metadata only; account inventories, prompts and credentials never enter this file. */
internal class OpenRouterCatalogSnapshotStore(private val file: File) {
    @Serializable
    data class Snapshot(
        val schema: Int = 1,
        val source: String = PUBLIC_SOURCE,
        val observedAtMillis: Long,
        val models: List<OpenRouterModelItem>
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun read(nowMillis: Long): Snapshot? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return null
        json.decodeFromString<Snapshot>(file.readText()).takeIf {
            it.schema == 1 &&
                it.source == PUBLIC_SOURCE &&
                it.observedAtMillis > 0 &&
                nowMillis - it.observedAtMillis in 0..OpenRouterCatalogLimits.MAX_AGE_MILLIS &&
                it.models.isNotEmpty() &&
                it.models.size <= 10_000 &&
                it.models.all { model -> model.id.length in 1..256 }
        }
    }.getOrNull()

    fun write(models: List<OpenRouterModelItem>, observedAtMillis: Long) {
        require(observedAtMillis > 0 && models.isNotEmpty() && models.size <= 10_000)
        val capabilities = models.map {
            it.copy(name = null, description = null, pricing = null, architecture = null, perRequestLimits = null)
        }
        val content = json.encodeToString(Snapshot(observedAtMillis = observedAtMillis, models = capabilities))
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("catalog-", ".tmp", directory)
        try {
            temporary.writeText(content)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val PUBLIC_SOURCE = "https://openrouter.ai/api/v1/models"
        private const val MAX_BYTES = 4L * 1024 * 1024
    }
}
