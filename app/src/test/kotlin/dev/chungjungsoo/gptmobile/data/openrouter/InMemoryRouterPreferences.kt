package dev.chungjungsoo.gptmobile.data.openrouter

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryRouterPreferences : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        transform(data.value).also { data.value = it }
    }
}
