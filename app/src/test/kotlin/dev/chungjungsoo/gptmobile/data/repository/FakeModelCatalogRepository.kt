package dev.melo.gptmobile.improved.data.repository

import dev.melo.gptmobile.improved.data.catalog.CatalogEntry

class FakeModelCatalogRepository(
    private val entries: List<CatalogEntry> = emptyList()
) : ModelCatalogRepository {
    var visibleEntriesCalls = 0
    var cachedVisibleEntriesCalls = 0

    override suspend fun getVisibleEntries(): List<CatalogEntry> {
        visibleEntriesCalls += 1
        return entries
    }

    override suspend fun getCachedVisibleEntries(): List<CatalogEntry> {
        cachedVisibleEntriesCalls += 1
        return entries
    }
}
