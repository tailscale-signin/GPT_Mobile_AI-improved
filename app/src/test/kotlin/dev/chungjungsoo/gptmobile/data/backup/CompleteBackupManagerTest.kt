package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringLane
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSuiteRegistry
import dev.chungjungsoo.gptmobile.data.database.AgentToolBindingDatabase
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.OpenRouterBatchCacheEntity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompleteBackupManagerTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabaseV2
    private lateinit var researchDatabase: dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
    private lateinit var researchBackup: dev.chungjungsoo.gptmobile.data.research.feature.ResearchBackup
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = TestVault()
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val legacy = mockk<AppBackupManager>(relaxed = true)
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var manager: CompleteBackupManager
    private lateinit var benchmarkStore: BenchmarkStore

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, "test-${UUID.randomUUID()}.db").addCallback(AgentToolBindingDatabase.callback).build()
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb") }
        coEvery { legacy.getBackupStatus() } returns BackupStatus()
        benchmarkStore = BenchmarkStore(context)
        researchDatabase = Room.inMemoryDatabaseBuilder(context, dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase::class.java).build()
        val bodies = dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore(context, researchDatabase)
        researchBackup = dev.chungjungsoo.gptmobile.data.research.feature.ResearchBackup(dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository(researchDatabase, bodies), bodies, database)
        manager = CompleteBackupManager(context, database, preferences, vault, settings, legacy, benchmarkStore = benchmarkStore, researchBackup = researchBackup)
    }

    @After
    fun cleanup() {
        database.close()
        researchDatabase.close()
        benchmarkStore.close()
        scope.cancel()
    }

    @Test
    fun conversationRestoreDoesNotReplaceProtectionAndExportsNeverContainSavedPassword() = runBlocking {
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
        val protection = BackupProtection(true, "persistent-password")
        manager.saveProtection(protection)
        val recreated = CompleteBackupManager(context, database, preferences, vault, settings, legacy, benchmarkStore = benchmarkStore, researchBackup = researchBackup)
        assertEquals(protection, recreated.savedProtection())
        val encrypted = File(context.cacheDir, "saved-password.gptbackup")
        val saved = manager.backup(Uri.fromFile(encrypted), selected, protection.password)
        assertTrue(saved.message, saved.success)
        assertTrue(manager.requiresPassword(Uri.fromFile(encrypted)))
        manager.saveProtection(BackupProtection(false, "changed-password"))
        val restored = manager.restore(Uri.fromFile(encrypted), protection.password, selected)
        assertTrue(restored.message, restored.success)
        assertEquals(BackupProtection(false, "changed-password"), recreated.savedProtection())

        val plain = File(context.cacheDir, "plain.gptbackup")
        manager.saveProtection(protection.copy(enabled = false))
        val plainSaved = manager.backup(Uri.fromFile(plain), selected, encrypt = false)
        assertTrue(plainSaved.message, plainSaved.success)
        assertFalse(manager.requiresPassword(Uri.fromFile(plain)))
        assertFalse(manager.requiresRecoveryKey(Uri.fromFile(plain)))
        manager.saveProtection(BackupProtection())
        val plainRestored = manager.restore(Uri.fromFile(plain), selection = selected)
        assertTrue(plainRestored.message, plainRestored.success)
        assertEquals(BackupProtection(), manager.savedProtection())
        java.util.zip.ZipFile(plain).use { archive ->
            val manifest = archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText()
            assertFalse(manifest.contains("persistent-password"))
            assertFalse(manifest.contains("changed-password"))
        }
    }

    @Test
    fun recoveryCorporaFollowBothHistoryAndResearchBackupSelections() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 7, title = "saved"))
        val directory = File(context.filesDir, "research-history/recovery-corpora")
        val corpus = dev.chungjungsoo.gptmobile.data.agent.RecoveryEvidenceCorpus.Builder().apply {
            add("tool-result", "result:receipt", "Private saved receipt")
            add("source", "source:S1", "Retained Research passage")
        }.build()
        val reference = dev.chungjungsoo.gptmobile.data.agent.RecoveryEvidenceCorpusStore(directory).retain(7, "7:1", "scope", corpus)
        val conversations = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
        val plain = File(context.cacheDir, "conversation-without-derived-history.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(plain), conversations, encrypt = false).success)
        java.util.zip.ZipFile(plain).use { archive ->
            assertFalse(archive.entries().asSequence().any { it.name.contains("recovery-corpora/") })
        }
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS, CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.CHAT_RESEARCH))
        val complete = File(context.cacheDir, "scoped-recovery-corpus.gptbackup")
        val saved = backupFixture(complete, selected)
        assertTrue(saved.message, saved.success)
        java.util.zip.ZipFile(complete).use { archive ->
            assertTrue(archive.getEntry("internal/research-history/recovery-corpora/$reference") != null)
        }
        assertTrue(directory.deleteRecursively())
        val restored = manager.restore(Uri.fromFile(complete), selection = conversations)
        assertTrue(restored.message, restored.success)
        assertFalse(File(directory, reference).exists())
    }

    @Test
    fun restoreReportsActualPhasesAndExtractionProgress() = runBlocking {
        val archive = File(context.cacheDir, "restore-progress.gptbackup")
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
        val saved = manager.backup(Uri.fromFile(archive), selected, encrypt = false)
        assertTrue(saved.message, saved.success)

        val stages = mutableListOf<RestoreProgress>()
        val restored = manager.restoreWithProgress(Uri.fromFile(archive), selection = selected, onProgress = stages::add)
        assertTrue(restored.message, restored.success)
        assertEquals(RestorePhase.READING, stages.first().phase)
        assertTrue(stages.any { it.phase == RestorePhase.EXTRACTING })
        assertTrue(stages.any { it.phase == RestorePhase.PREPARING })
        assertTrue(stages.any { it.phase == RestorePhase.APPLYING })
        assertEquals(RestorePhase.FINALIZING, stages.last().phase)
        assertTrue(stages.any { it.phase == RestorePhase.EXTRACTING && it.totalBytes > 0L && it.completedBytes == it.totalBytes })
    }

    @Test
    fun statisticsBackupPreservesBenchmarkRunsAndRebuildsScoreTable() = runBlocking {
        benchmarkStore.updateEnrollment(mapOf("profile-1" to "device-test"))
        benchmarkStore.save(
            BenchmarkRun(
                id = "benchmark-backup-roundtrip",
                profileUid = "profile-1",
                profileName = "Local test",
                provider = "LITERT_LM",
                model = "Gemma 3 1B",
                configKey = "device-test",
                local = true,
                mode = BenchmarkMode.QUICK,
                startedAt = System.currentTimeMillis(),
                suiteVersion = BenchmarkSuiteRegistry.VERSION,
                measurementVersion = 2,
                plannedTrials = 1,
                samples = listOf(
                    BenchmarkSample("instruction", "Exact instruction", "task", BenchmarkOutcome.PASSED)
                )
            )
        )
        val file = File(context.cacheDir, "benchmark-statistics.gptbackup")
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.STATISTICS))
        val saved = manager.backup(Uri.fromFile(file), selected, encrypt = false)
        assertTrue(saved.message, saved.success)

        benchmarkStore.delete("benchmark-backup-roundtrip")
        assertTrue(benchmarkStore.history.value.none { it.id == "benchmark-backup-roundtrip" })

        val restored = manager.restore(Uri.fromFile(file), selection = selected)
        assertTrue(restored.message, restored.success)
        assertTrue(benchmarkStore.history.value.any { it.id == "benchmark-backup-roundtrip" })
        assertTrue(benchmarkStore.snapshot.value?.rows?.any { it.profileUid == "profile-1" } == true)
    }

    @Test
    fun userBenchmarkReplacementIsRejectedBeforeEitherMergeOrClearMutatesChats() = runBlocking {
        val current = ChatRoomV2(id = 71, title = "Keep the current chat")
        database.chatRoomDao().addChatRoom(current)
        val pending = pendingBenchmarkRun()
        benchmarkStore.save(pending)
        val archive = File(context.cacheDir, "user-benchmark-ownership.gptbackup")
        val incoming = UserBackupData(chatRooms = listOf(ChatRoomV2(id = 72, title = "Imported chat")), benchmarkResults = "[]")
        archive.outputStream().use { AppBackupCrypto.encryptUserBackup(incoming, it, "test-password") }
        val user = UserBackupManager(context, database, benchmarkStore)
        for (clearExisting in listOf(false, true)) {
            val restored = user.importBackup(Uri.fromFile(archive), clearExisting, "test-password")
            assertTrue(restored.exceptionOrNull() is IllegalStateException)
            assertEquals("Remote benchmark work must be reconciled before saved results can be replaced.", restored.exceptionOrNull()?.message)
            assertEquals(listOf(current), database.chatRoomDao().getChatRooms())
            assertEquals(listOf(pending), benchmarkStore.history.value)
        }
        archive.outputStream().use { AppBackupCrypto.encryptUserBackup(incoming.copy(benchmarkResults = null), it, "test-password") }
        assertTrue(user.importBackup(Uri.fromFile(archive), passphrase = "test-password").isSuccess)
        assertEquals(setOf(71, 72), database.chatRoomDao().getChatRooms().map { it.id }.toSet())
        assertEquals(listOf(pending), benchmarkStore.history.value)
    }

    @Test
    fun encryptedUserClearRestoreRetainsPermanentPrivacyWithoutInventingMaskingHistory() = runBlocking {
        for (id in listOf(81, 82, 85)) {
            database.chatRoomDao().addChatRoom(ChatRoomV2(id = id, title = "Current private", privacyModeEnabled = true, privacyModeLocked = true))
        }
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 84, title = "Currently ordinary"))
        val incoming = kotlinx.serialization.json.Json.decodeFromString(
            UserBackupData.serializer(),
            """{"version":1,"chatRooms":[{"id":81,"title":"Legacy without privacy fields"},{"id":82,"title":"Older ordinary snapshot","privacyModeEnabled":false,"privacyModeLocked":false},{"id":83,"title":"New ordinary"},{"id":84,"title":"Saved private","privacyModeEnabled":true,"privacyModeLocked":true}]}"""
        )
        assertFalse(incoming.chatRooms.first().privacyModeEnabled)
        assertFalse(incoming.chatRooms.first().privacyModeLocked)
        val archive = File(context.cacheDir, "user-permanent-privacy.gptbackup")
        archive.outputStream().use { AppBackupCrypto.encryptUserBackup(incoming, it, "test-password") }
        val restored = UserBackupManager(context, database, benchmarkStore).importBackup(Uri.fromFile(archive), clearExisting = true, passphrase = "test-password")
        assertTrue(restored.exceptionOrNull()?.message, restored.isSuccess)
        val expected = mapOf(81 to (true to false), 82 to (true to false), 83 to (false to false), 84 to (true to true))
        suspend fun privacy() = database.chatRoomDao().getChatRooms().associate { it.id to (it.privacyModeLocked to it.privacyModeEnabled) }
        assertEquals(expected, privacy())
        val databaseName = checkNotNull(database.openHelper.databaseName)
        database.close()
        database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).addCallback(AgentToolBindingDatabase.callback).build()
        assertEquals(expected, privacy())
    }

    @Test
    fun completeBenchmarkReplacementIsRejectedBeforeOtherSelectedSectionsChange() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 73, title = "Saved chat"))
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS, CompleteBackupSection.STATISTICS))
        val archive = File(context.cacheDir, "complete-benchmark-ownership.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(archive), selected, encrypt = false).success)
        val current = ChatRoomV2(id = 73, title = "Current chat")
        database.chatRoomDao().addChatRoom(current)
        val pending = pendingBenchmarkRun()
        benchmarkStore.save(pending)
        val privacy = context.getSharedPreferences("privacy_personal_setup_v1", Context.MODE_PRIVATE)
        privacy.edit().putString("records", "current-personal-setup").putString("revision", "current-revision").commit()
        val restored = manager.restore(Uri.fromFile(archive), selection = selected)
        assertFalse(restored.success)
        assertEquals("Remote benchmark work must be reconciled before saved results can be replaced.", restored.message)
        assertEquals(listOf(current), database.chatRoomDao().getChatRooms())
        assertEquals("current-personal-setup", privacy.getString("records", null))
        assertEquals("current-revision", privacy.getString("revision", null))
        assertEquals(null, RestoreStoreJournal(vault).read())
        assertEquals(listOf(pending), benchmarkStore.history.value)

        val conversationsOnly = manager.restore(Uri.fromFile(archive), selection = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS)))
        assertTrue(conversationsOnly.message, conversationsOnly.success)
        assertEquals("Saved chat", database.chatRoomDao().getChatRooms().single().title)
        assertEquals(listOf(pending), benchmarkStore.history.value)
    }

    @Test
    fun statisticsRestoreWithoutBenchmarkPayloadPreservesPendingOwnership() = runBlocking {
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.STATISTICS))
        val archive = File(context.cacheDir, "statistics-without-benchmarks.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(archive), selected, encrypt = false).success)
        val extracted = File(context.cacheDir, "statistics-without-benchmarks").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, extracted, Long.MAX_VALUE)
        val files = manifest.files - "internal/benchmark-results/benchmark-results.json"
        CompleteBackupArchive.write(archive, manifest.copy(files = files), files.keys.associateWith { File(extracted, it) })
        val pending = pendingBenchmarkRun()
        benchmarkStore.save(pending)
        val restored = manager.restore(Uri.fromFile(archive), selection = selected)
        assertTrue(restored.message, restored.success)
        assertEquals(listOf(pending), benchmarkStore.history.value)
    }

    @Test
    fun lateBenchmarkOwnershipRetainsCurrentSectionAndCompletesRestore() = runBlocking {
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.STATISTICS))
        val archive = File(context.cacheDir, "late-benchmark-ownership.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(archive), selected, encrypt = false).success)
        val pending = pendingBenchmarkRun()
        var admitted = false
        val restored = manager.restoreWithProgress(Uri.fromFile(archive), selection = selected) { progress ->
            if (!admitted && progress.phase == RestorePhase.APPLYING) {
                admitted = true
                runBlocking { benchmarkStore.save(pending) }
            }
        }
        assertTrue(admitted)
        assertTrue(restored.message, restored.success)
        assertTrue(restored.message.contains(dev.chungjungsoo.gptmobile.data.benchmark.BENCHMARK_BACKUP_RETAINED_NOTICE))
        val journal = RestoreStoreJournal(vault)
        assertEquals(null, journal.read())
        assertEquals(null, committedRestoreId())
        assertEquals(listOf(pending), benchmarkStore.history.value)
        assertEquals(dev.chungjungsoo.gptmobile.data.benchmark.BENCHMARK_BACKUP_RETAINED_NOTICE, benchmarkStore.warning.value)
        manager.recoverAtStartup()
        assertEquals(listOf(pending), benchmarkStore.history.value)
    }

    @Test
    fun interruptedJournalFinalizationRetainsAnAlreadyImportedPendingBenchmarkAtStartup() = runBlocking {
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.STATISTICS))
        val pending = pendingBenchmarkRun()
        benchmarkStore.save(pending)
        val archive = File(context.cacheDir, "benchmark-import-before-journal-clear.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(archive), selected, encrypt = false).success)
        benchmarkStore.save(pending.copy(samples = emptyList(), finished = true, state = "COMPLETED"))
        benchmarkStore.delete(pending.id)
        val restored = manager.restoreWithProgress(Uri.fromFile(archive), selection = selected) { progress ->
            if (progress.phase == RestorePhase.APPLYING) vault.failNextDeleteReference = "restore-store-manifest-v1"
        }
        assertFalse(restored.success)
        assertEquals("Injected restore journal cleanup interruption", restored.message)
        val retained = benchmarkStore.history.value.single()
        assertEquals(pending.samples, retained.samples)
        val journal = RestoreStoreJournal(vault)
        val interrupted = checkNotNull(journal.read())
        assertEquals(interrupted.transactionId, committedRestoreId())

        benchmarkStore.close()
        benchmarkStore = BenchmarkStore(context)
        manager = CompleteBackupManager(context, database, preferences, vault, settings, legacy, benchmarkStore = benchmarkStore, researchBackup = researchBackup)
        manager.recoverAtStartup()
        assertEquals(null, journal.read())
        assertEquals(null, committedRestoreId())
        assertEquals(listOf(retained), benchmarkStore.history.value)
        assertEquals(dev.chungjungsoo.gptmobile.data.benchmark.BENCHMARK_BACKUP_RETAINED_NOTICE, benchmarkStore.warning.value)
        manager.recoverAtStartup()
        assertEquals(listOf(retained), benchmarkStore.history.value)
    }

    private fun committedRestoreId(): String? = database.openHelper.readableDatabase.query("SELECT transaction_id FROM gpt_internal_restore_commit WHERE singleton = 1").use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun pendingBenchmarkRun() = BenchmarkRun(
        "pending-remote", "profile", "Name", "OPENAI", "model", "key", false, BenchmarkMode.GITHUB_INTEGRATION, 1,
        samples = listOf(BenchmarkSample("case", "Case", "engineering", BenchmarkOutcome.PARTIAL, evidenceScope = BenchmarkEngineeringLane.LIVE_GITHUB.evidenceClass, cleanupReceipt = "gateway:submission_outcome_unknown", logicalRequestId = "original-remote-request")),
        finished = false, state = "AWAITING_REMOTE_RECONCILIATION"
    )

    @Test
    fun toolsRoundTripPreservesInstalledPackagesAndGlobalAndProfileToggles() = runBlocking {
        val registryFile = File(context.noBackupFilesDir, "native-marketplace-v1.json")
        val packageFile = File(context.noBackupFilesDir, "optional_marketplace_v1/optional-refuge.zip").apply { parentFile!!.mkdirs() }
        val unrelated = File(context.noBackupFilesDir, "unrelated-private-data").apply { writeText("stay local") }
        val entry = requireNotNull(dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find("optional-refuge"))
        val packageBytes = zipOf(
            "manifest.json" to dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceDownloadPolicy.manifest(entry),
            "README.md" to marketplaceReadme()
        )
        val registry = dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry(registryFile, vault)
        val manager = CompleteBackupManager(context, database, preferences, vault, settings, legacy, registry, benchmarkStore)
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.TOOLS))
        val pluginKey = androidx.datastore.preferences.core.stringPreferencesKey("advanced_feature_settings_json")
        val featureSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
            .withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.AMAZON_FREE, true)
            .withProfileToolPluginEnabled("owner", dev.chungjungsoo.gptmobile.data.model.ToolPluginId.AMAZON_FREE, true)
        registry.install(entry)
        registry.setEnabled(entry, true)
        registry.configure(entry, "", "", 4, 100)
        packageFile.writeBytes(packageBytes)
        preferences.edit { it[pluginKey] = kotlinx.serialization.json.Json.encodeToString(featureSettings) }
        val archive = File(context.cacheDir, "plugin-roundtrip.gptbackup")
        try {
            val saved = manager.backup(Uri.fromFile(archive), selected, encrypt = false)
            assertTrue(saved.message, saved.success)
            registry.uninstall(entry)
            packageFile.delete()
            preferences.edit {
                it[pluginKey] = kotlinx.serialization.json.Json.encodeToString(featureSettings.withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.AMAZON_FREE, false))
                it[intPreferencesKey("current_ui_preference")] = 77
            }
            val restored = manager.restore(Uri.fromFile(archive), selection = selected)
            assertTrue(restored.message, restored.success)
            val installation = registry.load().getValue(entry.id)
            assertTrue(installation.enabled)
            assertEquals(4, installation.maxResults)
            assertTrue(packageFile.readBytes().contentEquals(packageBytes))
            val features = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings>(requireNotNull(preferences.data.first()[pluginKey]))
            assertTrue(features.isToolPluginEnabledForProfile("owner", dev.chungjungsoo.gptmobile.data.model.ToolPluginId.AMAZON_FREE))
            assertEquals(77, preferences.data.first()[intPreferencesKey("current_ui_preference")])
            assertEquals("stay local", unrelated.readText())
        } finally {
            registryFile.delete()
            packageFile.delete()
            unrelated.delete()
        }
    }

    @Test
    fun completeRoundTripRestoresEveryTablePreferencesCredentialsAndPortableFiles() = runBlocking {
        val attachment = File(context.cacheDir, "photo.txt").apply { writeText("attachment data") }
        seed(attachment)
        preferences.edit {
            it[booleanPreferencesKey("gateway_stable")] = false
            it[intPreferencesKey("soft_rounds")] = 0
            it[stringSetPreferencesKey("favorite_groups")] = setOf("work", "personal")
        }
        context.getSharedPreferences("llama_settings", 0).edit().putString("advanced_settings", "{\"stableToolSurface\":false}").commit()
        vault.put("provider", "provider-token".toByteArray())
        vault.put("tool", "tool-token".toByteArray())
        vault.put("openrouter-management-local", "management-before".toByteArray())
        val archive = File(context.cacheDir, "complete.gptbackup")
        val saved = manager.backup(Uri.fromFile(archive), CompleteBackupSelection.ALL, password = "test-password")
        assertTrue(saved.message, saved.success)
        // Simulate changed data and a different attachment location/device.
        database.chatRoomDao().updateTitle(7, "changed", true)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        preferences.edit {
            it.clear()
            it[intPreferencesKey("new_setting")] = 99
        }
        context.getSharedPreferences("llama_settings", 0).edit().clear().commit()
        vault.put("provider", "changed-token".toByteArray())
        vault.put("extra", "extra-token".toByteArray())
        vault.put("openrouter-management-local", "management-after".toByteArray())
        attachment.delete()
        File(context.filesDir, "remove.txt").writeText("newer data")
        val restored = manager.restore(Uri.fromFile(archive), legacyPassword = "test-password")
        assertTrue(restored.message, restored.success)
        val chat = database.chatRoomDao().getChatRooms().single()
        assertEquals("saved", chat.title)
        assertTrue(chat.isFavorite)
        assertEquals("draft", chat.draftText)
        val message = database.messageDao().loadMessages(7).first { it.id == 11 }
        assertTrue(message.isFavorite)
        assertEquals("attachment data", File(message.attachments.single().localFilePath).readText())
        assertTrue(message.attachments.single().localFilePath.startsWith(context.filesDir.absolutePath))
        assertEquals("provider-token", vault.read("provider")!!.decodeToString())
        assertEquals("tool-token", vault.read("tool")!!.decodeToString())
        assertEquals("management-after", vault.read("openrouter-management-local")!!.decodeToString())
        assertFalse("extra" in vault.references())
        assertEquals(false, preferences.data.first()[booleanPreferencesKey("gateway_stable")])
        assertEquals(0, preferences.data.first()[intPreferencesKey("soft_rounds")])
        assertEquals(setOf("work", "personal"), preferences.data.first()[stringSetPreferencesKey("favorite_groups")])
        assertEquals(null, preferences.data.first()[intPreferencesKey("new_setting")])
        assertEquals("{\"stableToolSurface\":false}", context.getSharedPreferences("llama_settings", 0).getString("advanced_settings", null))
        // Restoring selected portable files must not erase unrelated app data.
        assertEquals("newer data", File(context.filesDir, "remove.txt").readText())
        assertEquals("model", database.chatPlatformModelDao().getByChatId(7).single().model)
        assertEquals("READY", database.localModelDao().getAll().single().status)
        assertEquals("model bytes", File(context.getExternalFilesDir(null), "models/local/revision/model.bin").readText())
        assertEquals("cached", database.openRouterBatchCacheDao().getByCacheKey("key")!!.responseContent)
        assertEquals("tool", database.toolConnectionDao().getAllConnections().single().secretRef)
        assertEquals("write_file", database.toolConnectionDao().listBindingsByProfile("profile").single().toolName)
        assertEquals("provider", database.platformDao().getPlatforms().single().secretRef)
        assertEquals("INTERRUPTED", database.agentRunDao().getById("run")!!.status)
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query("SELECT status FROM tool_events").use {
                assertTrue(it.moveToFirst())
                assertEquals("CANCELED", it.getString(0))
            }
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        verify { settings.invalidatePlatformCache() }
    }

    @Test
    fun legacyDuplicateBuiltInBindingsRestoreWithoutChangingTheArchiveOrRemoteBindings() = runBlocking {
        val dao = database.toolConnectionDao()
        database.platformDao().addPlatform(PlatformV2(uid = "profile-1", name = "First profile"))
        database.platformDao().addPlatform(PlatformV2(uid = "profile-2", name = "Second profile"))
        for (uid in listOf("remote-1", "remote-2")) {
            dao.upsertConnection(ToolConnection(uid, uid, uid, "MCP", "https://example.com", "NONE", null, null))
        }
        for (binding in listOf(
            AgentToolBinding("old", "profile-1", null, "read_url", 1),
            AgentToolBinding("other-profile", "profile-2", null, "read_url", 1),
            AgentToolBinding("other-tool", "profile-1", null, "current_date", 1),
            AgentToolBinding("remote-binding-1", "profile-1", "remote-1", "read_url", 1),
            AgentToolBinding("remote-binding-2", "profile-1", "remote-2", "read_url", 1)
        )) {
            dao.insertBinding(binding)
        }
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.TOOLS))
        val archive = File(context.cacheDir, "legacy-tool-bindings.gptbackup")
        val backedUp = backupFixture(archive, selection)
        assertTrue(backedUp.message, backedUp.success)
        val stage = File(context.cacheDir, "legacy-tools-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
            android.database.sqlite.SQLiteDatabase.openDatabase(File(stage, "database.sqlite").absolutePath, null, 0).use { old ->
                old.execSQL("DROP TRIGGER IF EXISTS agent_tool_binding_builtin_insert_unique")
                old.execSQL("DROP TRIGGER IF EXISTS agent_tool_binding_builtin_update_unique")
                old.execSQL("INSERT INTO agent_tool_bindings(binding_uid,profile_uid,connection_uid,tool_name,created_at) VALUES('newer-a','profile-1',NULL,'read_url',2),('newer-z','profile-1',NULL,'read_url',2)")
                old.rawQuery("SELECT COUNT(*) FROM agent_tool_bindings WHERE profile_uid='profile-1' AND connection_uid IS NULL AND tool_name='read_url'", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(3, rows.getInt(0))
                }
            }
            val sources = manifest.files.mapValues { (path, _) -> File(stage, path) }
            CompleteBackupArchive.write(archive, manifest.copy(files = sources.mapValues { it.value.length() }), sources)
            val originalBytes = archive.readBytes()

            val restored = manager.restore(Uri.fromFile(archive), selection = selection)

            assertTrue(restored.message, restored.success)
            assertTrue("Restoring must only normalize the staged database", originalBytes.contentEquals(archive.readBytes()))
            assertEquals(setOf("newer-z", "other-tool", "remote-binding-1", "remote-binding-2"), dao.listBindingsByProfile("profile-1").map { it.bindingUid }.toSet())
            assertEquals(listOf("other-profile"), dao.listBindingsByProfile("profile-2").map { it.bindingUid })
            assertEquals(setOf("remote-1", "remote-2"), dao.getAllConnections().map { it.connectionUid }.toSet())
            val rejected = runCatching {
                withContext(Dispatchers.IO) {
                    database.openHelper.writableDatabase.execSQL("INSERT INTO agent_tool_bindings(binding_uid,profile_uid,connection_uid,tool_name,created_at) VALUES('duplicate-after-restore','profile-1',NULL,'read_url',3)")
                }
            }.exceptionOrNull()
            assertTrue("The live restored database must retain the built-in constraint", rejected is android.database.sqlite.SQLiteConstraintException)
        } finally {
            stage.deleteRecursively()
        }
    }

    @Test
    fun modernBackupWithDifferentColumnOrderAndOldRoomIdentityRestoresIntoFreshSchema() = runBlocking {
        seed(File(context.cacheDir, "modern-file").apply { writeText("original") })
        val archive = File(context.cacheDir, "modern.gptbackup")
        assertTrue(backupFixture(archive).success)
        val stage = File(context.cacheDir, "rewrite-${UUID.randomUUID()}").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
        android.database.sqlite.SQLiteDatabase.openDatabase(File(stage, "database.sqlite").absolutePath, null, 0).use { db ->
            db.execSQL("UPDATE room_master_table SET identity_hash = 'old-installation-identity'")
            db.execSQL("CREATE TABLE reordered_cache (timestamp INTEGER NOT NULL, response_content TEXT NOT NULL, cache_key TEXT NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, retired_option TEXT)")
            db.execSQL("INSERT INTO reordered_cache(timestamp, response_content, cache_key, id) SELECT timestamp, response_content, cache_key, id FROM openrouter_batch_cache")
            db.execSQL("DROP TABLE openrouter_batch_cache")
            db.execSQL("ALTER TABLE reordered_cache RENAME TO openrouter_batch_cache")
        }
        val sources = manifest.files.mapValues { (path, _) -> File(stage, path) }
        CompleteBackupArchive.write(archive, manifest.copy(files = sources.mapValues { it.value.length() }), sources)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        database.chatRoomDao().updateTitle(7, "changed", true)
        val result = manager.restore(Uri.fromFile(archive))
        assertTrue(result.message, result.success)
        assertEquals("saved", database.chatRoomDao().getChatRooms().single().title)
        assertEquals("cached", database.openRouterBatchCacheDao().getByCacheKey("key")!!.responseContent)
        stage.deleteRecursively()
        Unit
    }

    @Test
    fun olderModernBackupMigratesNewTablesAndColumnsBeforeRestore() = runBlocking {
        seed(File(context.cacheDir, "older-file").apply { writeText("original") })
        val archive = File(context.cacheDir, "older.gptbackup")
        assertTrue(backupFixture(archive).success)
        val stage = File(context.cacheDir, "older-${UUID.randomUUID()}").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
        android.database.sqlite.SQLiteDatabase.openDatabase(File(stage, "database.sqlite").absolutePath, null, 0).use { db ->
            fun withoutColumns(table: String, omitted: Set<String>) {
                val definitions = mutableListOf<String>()
                val names = mutableListOf<String>()
                db.rawQuery("PRAGMA table_info(`$table`)", null).use { rows ->
                    while (rows.moveToNext()) {
                        val name = rows.getString(1)
                        if (name in omitted) continue
                        names += "`$name`"
                        definitions += "`$name` ${rows.getString(2)}" +
                            (if (rows.getInt(3) != 0) " NOT NULL" else "") +
                            (if (rows.getInt(5) != 0) " PRIMARY KEY" else "") +
                            (rows.getString(4)?.let { " DEFAULT $it" } ?: "")
                    }
                }
                db.execSQL("CREATE TABLE older_table (${definitions.joinToString()})")
                db.execSQL("INSERT INTO older_table (${names.joinToString()}) SELECT ${names.joinToString()} FROM `$table`")
                db.execSQL("DROP TABLE `$table`")
                db.execSQL("ALTER TABLE older_table RENAME TO `$table`")
            }
            withoutColumns("tool_connections", setOf("tool_policy", "approved_read_tools"))
            withoutColumns("knowledge_documents", setOf("deleted"))
            val triggers = db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'room_fts_content_sync_messages_search_%'", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            triggers.forEach { db.execSQL("DROP TRIGGER `$it`") }
            db.execSQL("DROP TABLE messages_search")
            db.execSQL("DROP TABLE tool_approvals")
            db.execSQL("DROP TABLE model_invocations")
            removeRoadmapColumns(db)
            db.version = 29
        }
        val sources = manifest.files.mapValues { (path, _) -> File(stage, path) }
        CompleteBackupArchive.write(archive, manifest.copy(files = sources.mapValues { it.value.length() }), sources)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        database.chatRoomDao().updateTitle(7, "changed", true)
        val result = manager.restore(Uri.fromFile(archive))
        assertTrue(result.message, result.success)
        assertEquals("saved", database.chatRoomDao().getChatRooms().single().title)
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        stage.deleteRecursively()
        Unit
    }

    @Test
    fun version30BackupAddsProfileAttributionWithoutLosingRequests() = runBlocking {
        seed(File(context.cacheDir, "v30-file").apply { writeText("original") })
        val archive = File(context.cacheDir, "v30.gptbackup")
        val selection = CompleteBackupSelection().toggled(CompleteBackupSection.AGENT_HISTORY, true)
        assertTrue(backupFixture(archive, selection).success)
        val stage = File(context.cacheDir, "v30-${UUID.randomUUID()}").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
        android.database.sqlite.SQLiteDatabase.openDatabase(File(stage, "database.sqlite").absolutePath, null, 0).use { db ->
            db.execSQL("DROP TABLE model_invocations")
            db.execSQL("CREATE TABLE model_invocations (id TEXT NOT NULL PRIMARY KEY, parentRunId TEXT NOT NULL, turnKey TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, kind TEXT NOT NULL, inputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL, estimated INTEGER NOT NULL, status TEXT NOT NULL, startedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, firstTokenMs INTEGER)")
            db.execSQL("INSERT INTO model_invocations VALUES ('primary', 'run', 'turn', 'provider', 'model', 'primary', 10, 20, 0, 'COMPLETED', 1000, 1500, 200)")
            db.execSQL("INSERT INTO model_invocations VALUES ('delegate', 'run', 'turn', 'provider', 'model', 'delegate', 10, 20, 0, 'COMPLETED', 1000, 1500, 200)")
            removeRoadmapColumns(db)
            db.version = 30
        }
        val sources = manifest.files.mapValues { (path, _) -> File(stage, path) }
        CompleteBackupArchive.write(archive, manifest.copy(files = sources.mapValues { it.value.length() }), sources)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        val result = manager.restore(Uri.fromFile(archive))
        assertTrue(result.message, result.success)
        val records = database.invocationDao().statistics().first().associateBy { it.id }
        assertEquals(2, records.size)
        assertEquals("profile", records.getValue("primary").profileUid)
        assertEquals(null, records.getValue("delegate").profileUid)
        assertEquals(20, records.getValue("primary").outputTokens)
        stage.deleteRecursively()
        Unit
    }

    @Test
    fun corruptBackupAndCredentialWriteFailureLeaveExistingStateIntact() = runBlocking {
        seed(File(context.cacheDir, "file").apply { writeText("old attachment") })
        vault.put("provider", "saved-token".toByteArray())
        val archive = File(context.cacheDir, "complete.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(archive), CompleteBackupSelection.ALL, password = "test-password").success)
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        database.chatRoomDao().updateTitle(7, "current", true)
        preferences.edit { it[intPreferencesKey("current")] = 7 }
        vault.put("provider", "current-token".toByteArray())
        File(context.filesDir, "current.txt").writeText("current file")
        val encryptedBytes = archive.readBytes()
        archive.writeBytes(
            encryptedBytes.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
        )
        assertFalse(manager.restore(Uri.fromFile(archive), legacyPassword = "test-password").success)
        archive.writeBytes(encryptedBytes)
        vault.failNextPut = true
        assertFalse(manager.restore(Uri.fromFile(archive), legacyPassword = "test-password").success)
        assertEquals("current", database.chatRoomDao().getChatRooms().single().title)
        assertEquals(7, preferences.data.first()[intPreferencesKey("current")])
        assertEquals("current-token", vault.read("provider")!!.decodeToString())
        assertEquals("current file", File(context.filesDir, "current.txt").readText())
    }

    @Test
    fun passwordlessBackupsAreAlwaysPortableAndNeverCreateInstallationBoundKeys() = runBlocking {
        seed(File(context.cacheDir, "portable-file").apply { writeText("portable") })
        val archive = File(context.cacheDir, "portable.gptbackup")

        val keyFile = File(context.cacheDir, "portable.gptkey")
        val saved = manager.backup(Uri.fromFile(archive), recoveryKeyUri = Uri.fromFile(keyFile))
        assertTrue(saved.message, saved.success)

        val header = archive.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
        assertEquals("GPTFULL3", header.decodeToString())
        assertTrue(keyFile.exists())
        assertFalse("Passwordless backups must never use GPTFULL2.", header.decodeToString().startsWith("GPTFULL2"))
        assertFalse("No installation-bound backup master key may be created.", "complete_backup_master_v2" in vault.references())
        assertFalse(manager.requiresPassword(Uri.fromFile(archive)))
        assertFalse(manager.requiresRecoveryKey(Uri.fromFile(archive)))
        vault.references().filter { it.startsWith("backup-recovery-") }.forEach { vault.delete(it) }
        assertTrue(manager.requiresRecoveryKey(Uri.fromFile(archive)))
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("UPDATE agent_runs SET status = 'INTERRUPTED'") }
        val recovered = manager.restore(Uri.fromFile(archive), recoveryKeyUri = Uri.fromFile(keyFile))
        assertTrue(recovered.message, recovered.success)

        val protected = File(context.cacheDir, "portable-protected.gptbackup")
        val protectedSaved = manager.backup(Uri.fromFile(protected), password = "portable-password")
        assertTrue(protectedSaved.message, protectedSaved.success)
        assertEquals("GPTFULL1", protected.inputStream().use { input -> ByteArray(8).also { input.read(it) } }.decodeToString())
        assertTrue(manager.requiresPassword(Uri.fromFile(protected)))
        assertFalse("Password encryption must also be independent of an installation key.", "complete_backup_master_v2" in vault.references())
    }

    @Test
    fun fiveYearHealthImportSurvivesPasswordlessEncryptedBackupAndRestore() = runBlocking {
        val store = dev.chungjungsoo.gptmobile.data.health.HealthConnectLocalImportStore(context)
        val snapshot = dev.chungjungsoo.gptmobile.data.health.HealthConnectLocalImport(
            importedAt = "2026-10-11T20:00:00Z",
            days = 1825,
            summary = kotlinx.serialization.json.buildJsonObject {
                put("schema", dev.chungjungsoo.gptmobile.data.health.HealthConnectTools.SCHEMA)
                put("status", "available")
                put(
                    "metrics",
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "steps",
                            kotlinx.serialization.json.buildJsonObject {
                                put("status", "available")
                            }
                        )
                    }
                )
            }
        )
        val archive = File(context.cacheDir, "health-years.gptbackup")
        val keyFile = File(context.cacheDir, "health-years.gptkey")
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.HEALTH_DATA))
        try {
            store.clear()
            store.save(snapshot)
            val saved = manager.backup(Uri.fromFile(archive), selection, recoveryKeyUri = Uri.fromFile(keyFile))
            assertTrue(saved.message, saved.success)
            val header = archive.inputStream().use { input ->
                ByteArray(8).also { input.read(it) }.decodeToString()
            }
            assertEquals("GPTFULL3", header)
            store.clear()
            val restored = manager.restore(Uri.fromFile(archive), selection = selection, recoveryKeyUri = Uri.fromFile(keyFile))
            assertTrue(restored.message, restored.success)
            assertEquals(snapshot, store.read())
        } finally {
            store.clear()
            archive.delete()
            keyFile.delete()
        }
    }

    @Test
    fun defaultsNeverExportCredentialsOrMemoryAndEncryptionRequiresRecoveryMaterial() = runBlocking {
        seed(File(context.cacheDir, "default-file").apply { writeText("file") })
        vault.put("provider", "sentinel-original-token".toByteArray())
        vault.put(dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository.VAULT_REFERENCE, "sentinel-memory".toByteArray())
        val archive = File(context.cacheDir, "default.gptbackup")
        assertTrue(backupFixture(archive).success)
        java.util.zip.ZipFile(archive).use { zip ->
            val manifest = zip.entries().asSequence().first { it.name.endsWith("manifest.json") }
            val contents = zip.getInputStream(manifest).bufferedReader().readText()
            assertFalse(contents.contains("sentinel-original-token"))
            assertFalse(contents.contains("sentinel-memory"))
            assertFalse(contents.contains(java.util.Base64.getEncoder().encodeToString("sentinel-original-token".toByteArray())))
        }
        vault.put("provider", "new-token".toByteArray())
        vault.put(dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository.VAULT_REFERENCE, "new-memory".toByteArray())
        assertFalse(manager.restore(Uri.fromFile(archive)).success)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("UPDATE agent_runs SET status = 'INTERRUPTED'") }
        val restored = manager.restore(Uri.fromFile(archive))
        assertTrue(restored.message, restored.success)
        assertEquals("new-token", vault.read("provider")!!.decodeToString())
        assertEquals("new-memory", vault.read(dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository.VAULT_REFERENCE)!!.decodeToString())
        assertFalse(manager.backup(Uri.fromFile(archive), CompleteBackupSelection(setOf(CompleteBackupSection.MEMORY))).success)
        assertFalse(manager.backup(Uri.fromFile(archive), CompleteBackupSelection(setOf(CompleteBackupSection.CREDENTIALS))).success)
        assertFalse(manager.backup(Uri.fromFile(archive), CompleteBackupSelection(setOf(CompleteBackupSection.HEALTH_DATA)), encrypt = false).success)
    }

    @Test
    fun memoryShardsFollowMemorySelectionAndNeverCredentialSelection() = runBlocking {
        val memory = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(vault, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
        memory.load()
        repeat(40) { memory.saveManual("Memory $it " + "x".repeat(890)) }
        assertTrue(vault.references().any { it.startsWith("memory-part-") })
        vault.put("provider", "first-token".toByteArray())
        val credentials = File(context.cacheDir, "credentials-only.gptbackup")
        val credentialsSaved = manager.backup(Uri.fromFile(credentials), CompleteBackupSelection(setOf(CompleteBackupSection.CREDENTIALS)), password = "passphrase")
        assertTrue(credentialsSaved.message, credentialsSaved.success)
        val memoryArchive = File(context.cacheDir, "memory-only.gptbackup")
        val memorySaved = manager.backup(Uri.fromFile(memoryArchive), CompleteBackupSelection(setOf(CompleteBackupSection.MEMORY)), password = "passphrase")
        assertTrue(memorySaved.message, memorySaved.success)
        memory.clear()
        val emptyKeys = vault.references().filter { it.startsWith("memory-part-") }.toSet()
        assertTrue(emptyKeys.isEmpty())
        assertTrue(manager.restore(Uri.fromFile(credentials), legacyPassword = "passphrase").success)
        assertEquals(emptyKeys, vault.references().filter { it.startsWith("memory-part-") }.toSet())
        memory.load()
        assertTrue(memory.state.value.facts.isEmpty())
        assertTrue(manager.restore(Uri.fromFile(memoryArchive), legacyPassword = "passphrase").success)
        memory.load()
        assertEquals(40, memory.state.value.facts.size)
        assertEquals("first-token", vault.read("provider")!!.decodeToString())
    }

    @Test fun gatewayAccountProofNeverExportsImportsOrReplacesLocalAuthority() = runBlocking {
        val localProof = "gateway_scope_" + "a".repeat(64)
        val forgedProof = "gateway_scope_" + "b".repeat(64)
        vault.put(localProof, "verified-only-on-this-installation".toByteArray())
        vault.put("provider", "saved-provider-credential".toByteArray())
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CREDENTIALS))
        val archive = File(context.cacheDir, "gateway-scope-exclusion.gptbackup")
        val saved = backupFixture(archive, selected)
        assertTrue(saved.message, saved.success)
        val stage = File(context.cacheDir, "gateway-scope-inspection").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
        assertTrue("provider" in manifest.secrets)
        assertFalse(manifest.secrets.keys.any { it.startsWith("gateway_scope_") })

        val encoded = java.util.Base64.getEncoder().encodeToString("incoming-account-proof".toByteArray())
        CompleteBackupArchive.write(archive, manifest.copy(secrets = manifest.secrets + mapOf(localProof to encoded, forgedProof to encoded)), manifest.files.mapValues { (path, _) -> File(stage, path) })
        vault.put("provider", "current-provider-credential".toByteArray())
        val restored = manager.restore(Uri.fromFile(archive), selection = selected)

        assertTrue(restored.message, restored.success)
        assertEquals("saved-provider-credential", vault.read("provider")!!.decodeToString())
        assertEquals("verified-only-on-this-installation", vault.read(localProof)!!.decodeToString())
        assertEquals(null, vault.read(forgedProof))
    }

    @Test fun privacyManagementKeyStaysLocalAndCredentialOnlyRestoreRevokesPersonalConfirmation() = runBlocking {
        val reference = "privacy_openrouter_management"
        vault.put(reference, "local-management-key".toByteArray())
        vault.put("provider", "saved-provider".toByteArray())
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CREDENTIALS))
        val archive = File(context.cacheDir, "privacy-credential-exclusion.gptbackup")
        val saved = backupFixture(archive, selected)
        assertTrue(saved.message, saved.success)
        val stage = File(context.cacheDir, "privacy-credential-inspection").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
        assertFalse(reference in manifest.secrets)
        val encoded = java.util.Base64.getEncoder().encodeToString("injected-management-key".toByteArray())
        CompleteBackupArchive.write(archive, manifest.copy(secrets = manifest.secrets + (reference to encoded)), manifest.files.mapValues { (path, _) -> File(stage, path) })
        val personal = context.getSharedPreferences("privacy_personal_setup_v1", Context.MODE_PRIVATE)
        val revision = UUID.randomUUID().toString()
        personal.edit().putString("revision", revision).putString("records", "local-confirmation").commit()
        val authority = context.getSharedPreferences("privacy_authority", Context.MODE_PRIVATE)
        authority.edit().putString("issuer", "local-issuer").putString("pending_guardrail_update", "unresolved-guardrail").commit()
        val pending = context.getSharedPreferences("privacy_setup_mutations_v1", Context.MODE_PRIVATE)
        pending.edit().putString("pending", "unresolved-setup").commit()

        val restored = manager.restore(Uri.fromFile(archive), selection = selected)

        assertTrue(restored.message, restored.success)
        assertEquals("local-management-key", vault.read(reference)!!.decodeToString())
        assertEquals("saved-provider", vault.read("provider")!!.decodeToString())
        assertEquals(null, personal.getString("records", null))
        assertFalse(personal.getString("revision", null) == revision)
        assertEquals("local-issuer", authority.getString("issuer", null))
        assertEquals("unresolved-guardrail", authority.getString("pending_guardrail_update", null))
        assertEquals("unresolved-setup", pending.getString("pending", null))
    }

    @Test fun platformOnlyRestoreRevokesPersonalConfirmationWithoutImportingPreferences() = runBlocking {
        database.platformDao().addPlatform(PlatformV2(uid = "privacy-profile", name = "Saved profile"))
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.PLATFORMS))
        val archive = File(context.cacheDir, "privacy-platform-restore.gptbackup")
        val saved = backupFixture(archive, selected)
        assertTrue(saved.message, saved.success)
        val personal = context.getSharedPreferences("privacy_personal_setup_v1", Context.MODE_PRIVATE)
        val revision = UUID.randomUUID().toString()
        personal.edit().putString("revision", revision).putString("records", "local-confirmation").commit()

        val restored = manager.restore(Uri.fromFile(archive), selection = selected)

        assertTrue(restored.message, restored.success)
        assertEquals(null, personal.getString("records", null))
        assertFalse(personal.getString("revision", null) == revision)
    }

    @Test fun failedCredentialRestoreCannotRevivePersonalConfirmationOrErasePendingPrivacyWrites() = runBlocking {
        vault.put("provider", "saved-provider".toByteArray())
        val selected = CompleteBackupSelection(setOf(CompleteBackupSection.CREDENTIALS))
        val archive = File(context.cacheDir, "privacy-restore-rollback.gptbackup")
        assertTrue(backupFixture(archive, selected).success)
        vault.put("provider", "current-provider".toByteArray())
        val personal = context.getSharedPreferences("privacy_personal_setup_v1", Context.MODE_PRIVATE)
        val revision = UUID.randomUUID().toString()
        personal.edit().putString("revision", revision).putString("records", "local-confirmation").commit()
        val pending = context.getSharedPreferences("privacy_setup_mutations_v1", Context.MODE_PRIVATE)
        pending.edit().putString("pending", "unresolved-setup").commit()
        vault.failNextReference = "provider"

        val restored = manager.restore(Uri.fromFile(archive), selection = selected)

        assertFalse(restored.success)
        assertEquals("current-provider", vault.read("provider")!!.decodeToString())
        assertEquals(null, personal.getString("records", null))
        assertFalse(personal.getString("revision", null) == revision)
        assertEquals("unresolved-setup", pending.getString("pending", null))
    }

    @Test fun legacyGitHubCredentialsCannotReconnectWithoutPortableEncryptedMetadata() = runBlocking {
        database.toolConnectionDao().upsertConnection(dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection("github-account", "GitHub", "github", "GITHUB", null, "OAUTH", "connection_github-account", null))
        vault.put("connection_github-account", "ghu_private_access".toByteArray())
        vault.put("github-oauth-session-private", "ghr_private_refresh".toByteArray())
        vault.put("github-public-auth-pending", "private-verifier".toByteArray())
        vault.put("github-account-binding-private", "public-account-1".toByteArray())
        vault.put("provider", "other-provider".toByteArray())
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.TOOLS, CompleteBackupSection.CREDENTIALS))
        val archive = File(context.cacheDir, "github-credential-policy.gptbackup")
        val result = backupFixture(archive, selection)
        assertTrue(result.message, result.success)
        val stage = File(context.cacheDir, "github-credential-inspection").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, stage, Long.MAX_VALUE)
        assertTrue("connection_github-account" in manifest.secrets)
        assertFalse("github-public-auth-pending" in manifest.secrets)
        assertTrue("provider" in manifest.secrets)
        // An older archive can still carry a raw token. Its connection metadata
        // must restore without that credential, even when credentials are selected.
        val legacy = manifest.copy(portableGitHubCredentials = false, secrets = manifest.secrets + ("connection_github-account" to java.util.Base64.getEncoder().encodeToString("ghu_old_archive".toByteArray())))
        CompleteBackupArchive.write(archive, legacy, manifest.files.mapValues { (path, _) -> File(stage, path) })
        val restored = manager.restore(Uri.fromFile(archive), selection = selection)
        assertTrue(restored.message, restored.success)
        assertEquals(null, database.toolConnectionDao().getConnection("github-account")?.secretRef)
        assertEquals(null, vault.read("connection_github-account"))
        assertEquals(null, vault.read("github-oauth-session-private"))
        assertEquals(null, vault.read("github-public-auth-pending"))
        assertEquals("public-account-1", vault.read("github-account-binding-private")!!.decodeToString())
        assertEquals("other-provider", vault.read("provider")!!.decodeToString())
    }

    @Test fun encryptedGitHubApiAndDraftsRoundTripWithoutRestoringPendingAuthorization() = runBlocking {
        val uid = "github-portable"
        val tokenRef = "connection_$uid"
        database.toolConnectionDao().upsertConnection(dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection(uid, "GitHub", "github", "GITHUB", null, "TOKEN", tokenRef, null))
        vault.put(tokenRef, "github_pat_portable_test".toByteArray())
        vault.put("github-public-auth-pending", "never-portable".toByteArray())
        val drafts = dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore(context, vault)
        drafts.write(uid, "saved work")
        context.getSharedPreferences("github_workspace", Context.MODE_PRIVATE).edit().putString(uid, "repository-selection").commit()
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.TOOLS, CompleteBackupSection.CREDENTIALS, CompleteBackupSection.GITHUB_WORKSPACE))
        val archive = File(context.cacheDir, "github-portable.gptbackup")
        val saved = manager.backup(Uri.fromFile(archive), selection, password = "test-password")
        assertTrue(saved.message, saved.success)
        assertFalse(archive.readBytes().decodeToString().contains("github_pat_portable_test"))
        vault.delete(tokenRef)
        vault.delete(dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore.KEY)
        drafts.write("another-workspace", "keep unrelated work under another key")
        drafts.write(uid, "changed work")
        context.getSharedPreferences("github_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        val restored = manager.restore(Uri.fromFile(archive), legacyPassword = "test-password", selection = selection)
        assertTrue(restored.message, restored.success)
        assertEquals(tokenRef, database.toolConnectionDao().getConnection(uid)?.secretRef)
        assertEquals("github_pat_portable_test", vault.read(tokenRef)!!.decodeToString())
        assertEquals("saved work", drafts.read(uid))
        assertEquals("keep unrelated work under another key", drafts.read("another-workspace"))
        assertEquals("repository-selection", context.getSharedPreferences("github_workspace", Context.MODE_PRIVATE).getString(uid, null))
        assertEquals(null, vault.read("github-public-auth-pending"))
        val plaintext = manager.backup(Uri.fromFile(File(context.cacheDir, "unsafe.gptbackup")), selection, encrypt = false)
        assertFalse(plaintext.success)
    }

    @Test fun encryptedGitHubOAuthRefreshSessionRestoresWithAccountRevalidation() = runBlocking {
        val uid = "github-oauth-portable"
        val token = "ghu_portable_test"
        val auth = dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
        val reference = "connection_$uid"
        database.toolConnectionDao().upsertConnection(dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection(uid, "GitHub", "github", "GITHUB", null, "OAUTH", reference, "client"))
        vault.put(reference, token.toByteArray())
        val session = dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator.Session(dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator.Account("123", "owner"), auth.fingerprint(token), "client", refreshToken = "ghr_portable_test", refreshState = "REAUTH_REQUIRED")
        vault.put(auth.sessionReference(uid), kotlinx.serialization.json.Json.encodeToString(session).toByteArray())
        val archive = File(context.cacheDir, "github-oauth-portable.gptbackup")
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.TOOLS, CompleteBackupSection.CREDENTIALS))
        assertTrue(manager.backup(Uri.fromFile(archive), selection, password = "test-password").success)
        vault.delete(auth.sessionReference(uid))
        val result = manager.restore(Uri.fromFile(archive), "test-password", selection)
        assertTrue(result.message, result.success)
        val restored = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator.Session>(vault.read(auth.sessionReference(uid))!!.decodeToString())
        assertEquals("ghr_portable_test", restored.refreshToken)
        assertEquals("REAUTH_REQUIRED", restored.refreshState)
        assertTrue(restored.verifyAfterRestore)
    }

    /** Structural migration tests inspect a decrypted archive, never a plaintext export. */
    private suspend fun backupFixture(archive: File, selection: CompleteBackupSelection = CompleteBackupSelection()): BackupRestoreResult {
        val keyFile = File(context.cacheDir, "${UUID.randomUUID()}.gptkey")
        val result = manager.backup(Uri.fromFile(archive), selection, recoveryKeyUri = Uri.fromFile(keyFile))
        if (result.success) {
            val key = keyFile.inputStream().use(CompleteBackupCrypto::readRecoveryKey)
            try {
                val decoded = File(context.cacheDir, "${UUID.randomUUID()}.zip")
                archive.inputStream().use { CompleteBackupCrypto.decryptPortable(it, decoded, key) }
                decoded.copyTo(archive, overwrite = true)
                decoded.delete()
            } finally {
                key.fill(0)
                keyFile.delete()
            }
        }
        return result
    }

    @Test fun encryptedLegacyUserBackupIsConvertedAndRespectsSelectedSections() = runBlocking {
        val input = File(context.cacheDir, "legacy-user.gptbackup")
        input.outputStream().use {
            AppBackupCrypto.encryptUserBackup(UserBackupData(chatRooms = listOf(ChatRoomV2(id = 91, title = "legacy")), messages = listOf(MessageV2(id = 92, chatId = 91, content = "preserved", platformType = null)), platforms = listOf(PlatformV2(uid = "old-profile", name = "Legacy profile", token = "private-token"))), it, "legacy-password")
        }
        val restored = manager.restore(Uri.fromFile(input), "legacy-password", CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS)))
        assertTrue(restored.message, restored.success)
        assertTrue(restored.message.contains("converted"))
        assertEquals("legacy", database.chatRoomDao().getChatRooms().single().title)
        assertTrue(database.platformDao().getPlatforms().isEmpty())
        val next = File(context.cacheDir, "converted-current.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(next), CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS)), encrypt = false).success)
        val manifest = CompleteBackupArchive.read(next, File(context.cacheDir, "converted-inspection").apply { mkdirs() }, Long.MAX_VALUE)
        assertEquals(LegacyBackupConverter.CURRENT_VERSION, manifest.version)
    }

    @Test fun wrongLegacyPasswordLeavesLiveDataAndPreferencesUnchanged() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Keep me"))
        preferences.edit { it[intPreferencesKey("existing")] = 7 }
        val input = File(context.cacheDir, "legacy-password.gptbackup")
        input.outputStream().use { AppBackupCrypto.encryptDatabase(DatabaseBackupPayload(chatRooms = listOf(ChatRoomV2(id = 2, title = "incoming"))), it, "correct-password") }
        assertFalse(manager.restore(Uri.fromFile(input), "wrong-password").success)
        assertEquals("Keep me", database.chatRoomDao().getChatRooms().single().title)
        assertEquals(7, preferences.data.first()[intPreferencesKey("existing")])
    }

    @Test fun statisticsRoundTripRestoresModelInvocationsAndDependentRuns() = runBlocking {
        seed(File(context.cacheDir, "statistics-attachment.txt").apply { writeText("fixture") })
        database.invocationDao().save(dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation("invocation", "run", "turn", "OPENAI", "model", "PRIMARY", 120, 45, status = "COMPLETED"))
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.STATISTICS))
        val output = File(context.cacheDir, "statistics.gptbackup")
        assertTrue(manager.backup(Uri.fromFile(output), selection, encrypt = false).success)
        database.invocationDao().clear()
        database.agentRunDao().updateStatus("run", "COMPLETED", null, null, null)
        val restored = manager.restore(Uri.fromFile(output), selection = selection)
        assertTrue(restored.message, restored.success)
        assertEquals(1, database.invocationDao().statistics().first().size)
        assertEquals(45, database.invocationDao().statistics().first().single().outputTokens)
    }

    private suspend fun seed(attachment: File) {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 7, title = "saved", enabledPlatform = listOf("profile"), isFavorite = true, draftText = "draft"))
        database.messageDao().addMessages(
            MessageV2(
                id = 11,
                chatId = 7,
                content = "question",
                platformType = null,
                isFavorite = true,
                attachments = listOf(ChatAttachment(attachment.absolutePath, "", "photo.txt", "text/plain", attachment.length()))
            ),
            MessageV2(id = 12, chatId = 7, content = "answer", platformType = "profile")
        )
        database.platformDao().addPlatform(PlatformV2(id = 9, uid = "profile", name = "saved", secretRef = "provider"))
        database.chatPlatformModelDao().upsertChatPlatformModel(ChatPlatformModelV2(7, "profile", "model"))
        database.toolConnectionDao().upsertConnection(
            dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection(
                "connection",
                "Example tools",
                "example_tools",
                "MCP",
                "https://example.com/mcp",
                "BEARER",
                "tool",
                null
            )
        )
        database.toolConnectionDao().insertBinding(AgentToolBinding("binding", "profile", "connection", "write_file"))
        database.agentRunDao().upsert(AgentRun("run", 7, 11, 12, "profile", "{}", "model", status = "RUNNING"))
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("INSERT INTO tool_events(event_id, run_id, sequence, call_id, tool_name, model_tool_name, arguments, status, is_error) VALUES ('event', 'run', 1, 'call', 'write_file', 'write_file', '{}', 'RUNNING', 0)")
        }
        File(context.getExternalFilesDir(null), "models/local/revision/model.bin").apply {
            parentFile!!.mkdirs()
            writeText("model bytes")
        }
        database.localModelDao().upsert(LocalModel("local", "revision", "model.bin", "models/local/revision", 11, "READY"))
        database.openRouterBatchCacheDao().insertOrUpdate(OpenRouterBatchCacheEntity(cacheKey = "key", responseContent = "cached"))
    }

    private class TestVault : SecretVault {
        private val values = mutableMapOf<String, ByteArray>()
        var failNextPut = false
        var failNextReference: String? = null
        var failNextDeleteReference: String? = null
        override suspend fun put(secretRef: String, secret: ByteArray) {
            if (failNextPut || secretRef == failNextReference) {
                failNextPut = false
                failNextReference = null
                error("Injected credential write failure")
            }
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            if (secretRef == failNextDeleteReference) {
                failNextDeleteReference = null
                error("Injected restore journal cleanup interruption")
            }
            values.remove(secretRef)
        }
        override suspend fun references() = values.keys.toSet()
    }

    private fun marketplaceReadme(): ByteArray = listOf(
        java.io.File("mcp/marketplace/README.md"),
        java.io.File("../mcp/marketplace/README.md")
    ).first { it.isFile }.readBytes()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun removeRoadmapColumns(db: android.database.sqlite.SQLiteDatabase) {
        // These fixtures predate gateway persistence; retaining its current tables
        // would not represent a version 29 or 30 backup.
        for (table in listOf(
            "gateway_message_parts",
            "gateway_messages",
            "gateway_submissions",
            "gateway_engineering_events",
            "gateway_engineering_bindings",
            "gateway_migrations",
            "gateway_jobs",
            "gateway_conversations"
        )) {
            db.execSQL("DROP TABLE IF EXISTS `$table`")
        }
        db.execSQL("DROP TABLE IF EXISTS reply_publications")
        db.execSQL("DROP TABLE IF EXISTS conversation_folder_moves")
        db.execSQL("DROP TABLE IF EXISTS workspace_records")
        db.execSQL("DROP TABLE IF EXISTS answer_segments")
        for ((table, columns) in mapOf(
            "chats_v2" to listOf("is_temporary", "parent_chat_id", "branch_message_id", "draft_attachments", "last_share_token", "combined_primary_uid"),
            "knowledge_projects" to listOf("includePersonalMemory", "defaultProfileUid"),
            "messages_v2" to listOf("ownership_fence"),
            "agent_runs" to listOf("ownership_fence", "synthesis_key", "synthesis_snapshot", "display_role")
        )) {
            val definitions = mutableListOf<String>()
            val names = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { rows ->
                while (rows.moveToNext()) {
                    val name = rows.getString(1)
                    if (name in columns) continue
                    names += "`$name`"
                    definitions += "`$name` ${rows.getString(2)}" +
                        (if (rows.getInt(3) != 0) " NOT NULL" else "") +
                        (if (rows.getInt(5) != 0) " PRIMARY KEY" else "") +
                        (rows.getString(4)?.let { " DEFAULT $it" } ?: "")
                }
            }
            db.execSQL("CREATE TABLE roadmap_old_table (${definitions.joinToString()})")
            db.execSQL("INSERT INTO roadmap_old_table (${names.joinToString()}) SELECT ${names.joinToString()} FROM `$table`")
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE roadmap_old_table RENAME TO `$table`")
        }
    }
}
