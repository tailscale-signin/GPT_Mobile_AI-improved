package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry

interface ModelCatalogRepository {
    suspend fun getSnapshot(refresh: Boolean = false): dev.chungjungsoo.gptmobile.data.catalog.CatalogSnapshot =
        dev.chungjungsoo.gptmobile.data.catalog.CatalogSnapshot(if (refresh) getVisibleEntries() else getCachedVisibleEntries())
    suspend fun getVisibleEntries(): List<CatalogEntry>

    suspend fun getCachedVisibleEntries(): List<CatalogEntry> = getVisibleEntries()
}
