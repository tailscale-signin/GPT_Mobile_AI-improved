package dev.chungjungsoo.gptmobile.domain

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.domain.model.SortType
import dev.chungjungsoo.gptmobile.domain.usecase.ArchiveConversationUseCase
import dev.chungjungsoo.gptmobile.domain.usecase.ManagePlatformsUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class Phase2DomainUseCasesTest {

    @Test
    fun archiveConversationUseCase_callsRepositoryMethods() = runTest {
        val chatRepo = mock(ChatRepository::class.java)
        val useCase = ArchiveConversationUseCase(chatRepo)

        useCase.archiveChat(42)
        verify(chatRepo).setChatArchived(42, true)

        useCase.unarchiveChat(42)
        verify(chatRepo).setChatArchived(42, false)

        val sampleList = listOf(ChatRoomV2(id = 42, title = "Archived", isArchived = true))
        `when`(chatRepo.fetchArchivedChatListV2()).thenReturn(sampleList)

        val result = useCase.getArchivedChats()
        assertEquals(1, result.size)
        assertTrue(result.first().isArchived)
    }

    @Test
    fun managePlatformsUseCase_sortPlatforms() {
        val settingRepo = mock(SettingRepository::class.java)
        val useCase = ManagePlatformsUseCase(settingRepo)

        val p1 = PlatformV2(id = 1, name = "Zebra", enabled = false, isFavorite = false, compatibleType = ClientType.OPENAI, apiUrl = "http://a")
        val p2 = PlatformV2(id = 2, name = "Apple", enabled = true, isFavorite = false, compatibleType = ClientType.OPENAI, apiUrl = "http://b")
        val p3 = PlatformV2(id = 3, name = "Banana", enabled = false, isFavorite = true, compatibleType = ClientType.OPENAI, apiUrl = "http://c")

        val sortedByName = useCase.sortPlatforms(listOf(p1, p2, p3), SortType.NAME)
        assertEquals(listOf("Apple", "Banana", "Zebra"), sortedByName.map { it.name })

        val sortedByEnabled = useCase.sortPlatforms(listOf(p1, p2, p3), SortType.ENABLED)
        assertEquals(listOf("Apple", "Banana", "Zebra"), sortedByEnabled.map { it.name })

        val sortedByFavorites = useCase.sortPlatforms(listOf(p1, p2, p3), SortType.FAVORITES)
        assertEquals(listOf("Banana", "Apple", "Zebra"), sortedByFavorites.map { it.name })
    }

    @Test
    fun managePlatformsUseCase_labelsParsing() {
        val settingRepo = mock(SettingRepository::class.java)
        val useCase = ManagePlatformsUseCase(settingRepo)

        val labels = listOf("work", "personal", "fast")
        val json = "[\"work\",\"personal\",\"fast\"]"
        val parsed = useCase.parseLabels(json)
        assertEquals(labels, parsed)

        assertTrue(useCase.parseLabels(null).isEmpty())
        assertTrue(useCase.parseLabels("invalid-json").isEmpty())
    }
}
