package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.converter.AssistantRevisionListConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDatabaseV2MigrationsTest {

    @Test
    fun `migration instances have correct versions`() {
        ChatDatabaseV2Migrations.ALL_MIGRATIONS.forEach { migration ->
            assertEquals(migration.startVersion + 1, migration.endVersion)
        }

        assertEquals(10, ChatDatabaseV2Migrations.ALL_MIGRATIONS.first().startVersion)
        assertEquals(20, ChatDatabaseV2Migrations.ALL_MIGRATIONS.last().endVersion)
    }

    @Test
    fun `corrupt assistant revision json decodes to empty list`() {
        val revisions = AssistantRevisionListConverter().fromString("[")

        assertTrue(revisions.isEmpty())
    }
}
