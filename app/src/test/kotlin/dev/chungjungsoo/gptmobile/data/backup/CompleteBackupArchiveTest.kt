package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompleteBackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun archivePreservesFilesAndExplicitPreferenceValues() {
        val sources = mapOf("database.sqlite" to temp.newFile("db").apply { writeText("db") }, "internal/attachments/file" to temp.newFile("file").apply { writeText("attachment") })
        val manifest = CompleteBackupManifest(
            preferences = mapOf("disabled" to BackupValue("boolean", "false"), "zero" to BackupValue("int", "0")),
            sharedPreferences = mapOf("llama_settings" to mapOf("advanced_settings" to BackupValue("string", "{}"))),
            secrets = mapOf("credential" to "c2VjcmV0"),
            files = sources.mapValues { it.value.length() }
        )
        val archive = temp.newFile("backup.zip")
        CompleteBackupArchive.write(archive, manifest, sources)
        val staging = temp.newFolder("staging")
        assertEquals(manifest, CompleteBackupArchive.read(archive, staging, 1024))
        assertEquals("attachment", File(staging, "internal/attachments/file").readText())
    }

    @Test
    fun traversalUnknownEntriesAndInsufficientSpaceAreRejected() {
        for (path in listOf("../outside", "internal/../outside", "internal/a/../../outside", "internal/datastore/config", "/internal/file", "internal/C:\\file")) {
            assertThrows(IllegalArgumentException::class.java) { CompleteBackupArchive.validatePath(path) }
        }
        val manifest = CompleteBackupManifest(preferences = emptyMap(), sharedPreferences = emptyMap(), secrets = emptyMap(), files = mapOf("database.sqlite" to 2L))
        val archive = temp.newFile("bad.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in mapOf("manifest.json" to Json.encodeToString(manifest), "database.sqlite" to "db", "extra" to "unexpected")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) { CompleteBackupArchive.read(archive, temp.newFolder("stage"), 1024) }
        CompleteBackupArchive.write(archive, manifest, mapOf("database.sqlite" to temp.newFile("db").apply { writeText("db") }))
        assertThrows(IllegalArgumentException::class.java) { CompleteBackupArchive.read(archive, temp.newFolder("small"), 1) }
    }

    @Test
    fun failedFileInstallationRestoresOriginalFiles() {
        val internal = temp.newFolder("internal")
        val external = temp.newFolder("external")
        val existing = File(internal, "new").apply { writeText("original") }
        val datastore = File(internal, "datastore/prefs").apply {
            parentFile!!.mkdirs()
            writeText("live preferences")
        }
        val staging = temp.newFolder("staging")
        File(staging, "internal/new/child.txt").apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        val replacement = CompleteBackupFiles(mapOf("internal" to internal, "external" to external))
            .replacement(staging, linkedSetOf("internal/new/child.txt", "external/missing.txt"))
        assertThrows(Exception::class.java) { replacement.apply() }
        replacement.rollback()
        assertEquals("original", existing.readText())
        assertEquals("live preferences", datastore.readText())
        assertFalse(File(internal, "new/child.txt").exists())
    }

    @Test
    fun interruptedRestoreRecoversFilesWhenDatabaseDidNotCommit() {
        val internal = temp.newFolder("journal-internal")
        val external = temp.newFolder("journal-external")
        val journal = File(temp.root, "restore-journal.json")
        val original = File(internal, "attachment.bin").apply { writeText("original") }
        val staging = temp.newFolder("journal-stage")
        File(staging, "internal/attachment.bin").apply {
            parentFile!!.mkdirs()
            writeText("restored")
        }
        val storage = CompleteBackupFiles(mapOf("internal" to internal, "external" to external), transactionJournal = journal)

        storage.replacement(staging, setOf("internal/attachment.bin"), "interrupted").apply()
        assertEquals("restored", original.readText())
        storage.recoverInterruptedRestore(committedTransactionId = null)

        assertEquals("original", original.readText())
        assertFalse(journal.exists())
    }

    @Test
    fun committedRestoreRecoveryKeepsNewFilesAndCleansJournal() {
        val internal = temp.newFolder("committed-internal")
        val external = temp.newFolder("committed-external")
        val journal = File(temp.root, "committed-restore-journal.json")
        val staging = temp.newFolder("committed-stage")
        File(staging, "internal/new.bin").apply {
            parentFile!!.mkdirs()
            writeText("restored")
        }
        val storage = CompleteBackupFiles(mapOf("internal" to internal, "external" to external), transactionJournal = journal)

        storage.replacement(staging, setOf("internal/new.bin"), "committed").apply()
        storage.recoverInterruptedRestore(committedTransactionId = "committed")

        assertEquals("restored", File(internal, "new.bin").readText())
        assertFalse(journal.exists())
    }
}
