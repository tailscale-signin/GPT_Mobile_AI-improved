package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Presentation preference only. It never changes the room's execution mode or AI profile. */
@Singleton
class VoiceRoomPresentationStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mutable = MutableStateFlow<Set<VoiceRoomIdentity>?>(null)
    val rooms = mutable.asStateFlow()

    init {
        scope.launch {
            lock.withLock {
                val saved = runCatching { preferences().all }.getOrDefault(emptyMap())
                val ids = saved.mapNotNull { (key, value) -> VoiceRoomIdentity.fromPreference(key, value) }.toSet()
                mutable.value = ids + mutable.value.orEmpty()
            }
        }
    }

    fun rememberVoiceRoom(room: VoiceRoomIdentity?) {
        if (room == null) return
        scope.launch {
            lock.withLock {
                if (room in mutable.value.orEmpty()) return@withLock
                // Keep the current conversation usable even if persistence is temporarily unavailable.
                mutable.value = mutable.value.orEmpty() + room
                runCatching { preferences().edit().putBoolean(room.preferenceKey, true).commit() }
            }
        }
    }

    private fun preferences() = context.getSharedPreferences("voice_room_presentation", Context.MODE_PRIVATE)
}

@HiltViewModel
internal class VoiceRoomPresentationViewModel @Inject constructor(private val store: VoiceRoomPresentationStore) : ViewModel() {
    val rooms = store.rooms
    fun rememberVoiceRoom(room: VoiceRoomIdentity?) = store.rememberVoiceRoom(room)
}
