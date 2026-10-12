package dev.chungjungsoo.gptmobile.data.catalog

import java.util.Locale
import kotlin.math.max
import kotlinx.serialization.json.Json

object ModelCatalogParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun supported(version: Int) = version in 1..2
    fun parse(rawJson: String): ModelCatalog {
        require(rawJson.length <= 2_000_000)
        val catalog = json.decodeFromString<ModelCatalog>(rawJson)
        require(catalog.models.map { it.id }.distinct().size == catalog.models.size)
        if (catalog.schemaVersion == 2) {
            val source = requireNotNull(catalog.source) { "V2 catalog requires source coverage" }
            require(source.complete && source.artifactCount == catalog.coverage.size && source.familyCount == catalog.coverage.map { it.familyId }.distinct().size)
            require(source.inputDigest.matches(Regex("[a-f0-9]{64}")))
            require(catalog.coverage.map { it.artifactId }.distinct().size == catalog.coverage.size)
            require(catalog.coverage.all { it.status in setOf("represented", "unsupported_platform", "unsupported_app_workflow", "missing_artifact_metadata", "withdrawn") })
        }
        return catalog
    }

    fun visibleEntries(catalog: ModelCatalog, currentAppVersion: String): List<CatalogEntry> {
        if (!supported(catalog.schemaVersion)) {
            return emptyList()
        }
        return catalog.models.filter { entry ->
            entry.id.isNotBlank() && compareAppVersions(entry.minAppVersion, currentAppVersion) <= 0
        }
    }

    fun formatDownloadSize(sizeInBytes: Long): String {
        val kilobyte = 1024.0
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024
        return when {
            sizeInBytes >= gigabyte -> {
                val value = sizeInBytes / gigabyte
                val pattern = if (value >= 10) "%.0f GB" else "%.1f GB"
                String.format(Locale.US, pattern, value)
            }

            sizeInBytes >= megabyte -> "${(sizeInBytes / megabyte).toInt()} MB"

            else -> "${(sizeInBytes / kilobyte).toInt()} KB"
        }
    }

    internal fun compareAppVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val length = max(leftParts.size, rightParts.size)
        for (index in 0 until length) {
            val leftValue = leftParts.getOrElse(index) { 0 }
            val rightValue = rightParts.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> = version.split('.').map { part ->
        part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}
