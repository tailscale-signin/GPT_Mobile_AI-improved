    selectedProfileUids: List<String>,
    configuredPlatforms: List<PlatformV2>
): List<IndexedValue<PlatformV2>> {
    val platformsByUid = configuredPlatforms.associateBy(PlatformV2::uid)
    return selectedProfileUids.mapIndexedNotNull { index, uid ->
        platformsByUid[uid]?.let { IndexedValue(index, it) }
    }
}

internal suspend fun <T> persistBeforeProvider(
    persist: suspend () -> T,
    startProvider: suspend (T) -> Unit,
    onFailure: suspend (Throwable) -> Unit
) {
    val persisted = try {
        persist()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
        return
    }
    startProvider(persisted)
}

internal fun mergePersistedAssistantRow(