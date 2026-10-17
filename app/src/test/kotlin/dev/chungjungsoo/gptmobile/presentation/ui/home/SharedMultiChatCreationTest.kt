package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SharedMultiChatCreationTest {
    @Test
    fun multiChatCreatesOneRoomContainingEverySelectedProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val profiles = listOf(PlatformV2(uid = "a", name = "Alpha"), PlatformV2(uid = "b", name = "Beta"))
            val created = mutableListOf<ChatRoomV2>()
            var features = AppFeatureSettings()
            val settings = proxy<SettingRepository> { name, args ->
                when (name) {
                    "observePlatformV2s" -> flowOf(profiles)
                    "observeFeatureSettings" -> flowOf(features)
                    "fetchPlatformV2s" -> profiles
                    "mutateFeatureSettings" -> {
                        @Suppress("UNCHECKED_CAST")
                        val transform = args[0] as (AppFeatureSettings) -> AppFeatureSettings
                        features = transform(features)
                        features
                    }
                    else -> error("Unexpected settings operation: $name")
                }
            }
            val chats = proxy<ChatRepository> { name, args ->
                check(name == "createDraftChat") { "Unexpected chat operation: $name" }
                @Suppress("UNCHECKED_CAST")
                val uids = args[0] as List<String>
                ChatRoomV2(id = 42, title = args[2] as String, enabledPlatform = uids, conversationMode = args[1] as String)
                    .also(created::add)
            }
            val viewModel = NewChatViewModel(
                SavedStateHandle(mapOf("new_chat_type" to "MULTI_CHAT", "new_chat_uids" to listOf("a", "b"))),
                settings,
                chats
            )
            store.put("new-chat", viewModel)
            var route = ""
            viewModel.start { route = it }
            runCurrent()
            assertEquals(1, created.size)
            assertEquals(listOf("a", "b"), created.single().enabledPlatform)
            assertEquals(ConversationMode.STANDARD, created.single().conversationMode)
            assertEquals("chat_room/42?enabled=a,b&mode=STANDARD", route)
            assertTrue(features.multiChatWorkspaces.isEmpty())
            viewModel.start { route = it }
            runCurrent()
            assertEquals(1, created.size)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T
}
