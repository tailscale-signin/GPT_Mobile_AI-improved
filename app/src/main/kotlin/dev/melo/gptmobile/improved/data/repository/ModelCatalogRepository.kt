package dev.melo.gptmobile.improved.data.repository

import dev.melo.gptmobile.improved.data.catalog.CatalogEntry

interface ModelCatalogRepository {
    suspend fun getVisibleEntries(): List<CatalogEntry>

    suspend fun getCachedVisibleEntries(): List<CatalogEntry> = getVisibleEntries()
}
