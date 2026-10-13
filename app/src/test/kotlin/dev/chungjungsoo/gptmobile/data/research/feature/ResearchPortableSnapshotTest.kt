package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSelection
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchPortableSnapshotTest {
    @Test fun encryptedSectionRoundTripPreservesVersionsAndRejectsCorruptBodies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = kotlin.io.path.createTempDirectory("research-portable").toFile()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        val chat = Room.inMemoryDatabaseBuilder(app, ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        try {
            val store = ResearchSessionStore(context, db)
            val archive = ResearchArchiveRepository(db, store)
            val source = ResearchSource("S1", "https://example.org/paper", "Synthetic trial", status = "Read", doi = "10.1234/trial", retrievedAt = 1000, passage = "Methods and results")
            val version = archive.capture(source, "Methods and results", "public", true, 7, "original", true, 0)
            val selection = CompleteBackupSelection(setOf(CompleteBackupSection.RESEARCH_ARCHIVE))
            assertTrue(selection.requiresEncryption)
            val backup = ResearchBackup(archive, store, chat)
            val work = File(root, "export").apply { mkdirs() }
            val files = backup.export(selection, work)
            val staging = File(root, "staging").apply { mkdirs() }
            files.forEach { (path, file) ->
                File(staging, path).apply {
                    parentFile.mkdirs()
                    file.copyTo(this)
                }
            }
            val snapshot = backup.validate(File(staging, "research-feature.json"), staging)
            assertEquals(version.contentHash, snapshot.versions.single().contentHash)
            backup.restore(snapshot, selection, researchHash("backup-one"), staging)
            backup.restore(snapshot, selection, researchHash("backup-one"), staging)
            assertEquals(1, db.research().count())
            assertEquals(version, db.research().version(version.versionId))
            val chunk = files.keys.first { it.endsWith(".chunk") }
            File(staging, chunk).writeText("Corrupted evidence")
            assertTrue(runCatching { backup.validate(File(staging, "research-feature.json"), staging) }.isFailure)
        } finally {
            db.close()
            chat.close()
            root.deleteRecursively()
        }
    }
}
