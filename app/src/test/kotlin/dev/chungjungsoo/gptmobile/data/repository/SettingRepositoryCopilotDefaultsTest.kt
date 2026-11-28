package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSource
import dev.chungjungsoo.gptmobile.data.github.GitHubCopilotDefaults
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRepositoryCopilotDefaultsTest {
    @Test fun repositoryPersistsRolloutOnceAndAUserOffSurvivesRereadAndRestart() = runBlocking {
        var persisted = AppFeatureSettings(pluginExecution = mapOf(ToolPluginId.GITHUB to PluginExecutionSettings(githubCopilotEnabled = false)))
        var writes = 0
        val source = mockk<SettingDataSource>()
        coEvery { source.getFeatureSettings() } coAnswers { persisted }
        coEvery { source.mutateFeatureSettings(any()) } coAnswers {
            persisted = firstArg<(AppFeatureSettings) -> AppFeatureSettings>().invoke(persisted)
            writes++
            persisted
        }
        val profiles = mockk<PlatformV2Dao>()
        coEvery { profiles.getPlatforms() } returns emptyList()
        fun repository() = SettingRepositoryImpl(source, profiles, mockk(), mockk(), mockk())
        val first = repository().getFeatureSettings()
        assertEquals(GitHubCopilotDefaults.VERSION, first.githubCopilotDefaultsVersion)
        assertTrue(first.pluginExecution.getValue(ToolPluginId.GITHUB).githubCopilotEnabled)
        assertEquals(1, writes)
        repository().getFeatureSettings()
        assertEquals(1, writes)
        repository().mutateFeatureSettings { current ->
            current.copy(pluginExecution = current.pluginExecution + (ToolPluginId.GITHUB to current.pluginExecution.getValue(ToolPluginId.GITHUB).copy(githubCopilotEnabled = false)))
        }
        assertEquals(2, writes)
        assertFalse(repository().getFeatureSettings().pluginExecution.getValue(ToolPluginId.GITHUB).githubCopilotEnabled)
        assertEquals(2, writes)
    }
}
