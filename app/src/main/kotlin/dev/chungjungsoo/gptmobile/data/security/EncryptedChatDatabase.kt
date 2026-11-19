package dev.chungjungsoo.gptmobile.data.security

import android.content.Context
import android.system.Os
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Installed databases use a device key. Portable backup snapshots use their own format. */
internal class EncryptedChatDatabase(context: Context) : SupportSQLiteOpenHelper.Factory {
    private val vault = AndroidSecretVault(context.applicationContext, "gptmobile_chat_database_v1", "chat-database-keys")

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        object : SupportSQLiteOpenHelper {
            override val databaseName = requireNotNull(configuration.name)
            private var wal = false
            private val helper = lazy {
                val file = configuration.context.getDatabasePath(databaseName)
                val key = prepare(file, vault)
                SupportOpenHelperFactory(key).create(configuration).also { it.setWriteAheadLoggingEnabled(wal) }
            }

            // Room configures WAL during DI. Defer native loading, Keystore and migration until Room opens on IO.
            @Synchronized
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                wal = enabled
                if (helper.isInitialized()) helper.value.setWriteAheadLoggingEnabled(enabled)
            }

            override val writableDatabase: SupportSQLiteDatabase
                @Synchronized get() = helper.value.writableDatabase
            override val readableDatabase: SupportSQLiteDatabase
                @Synchronized get() = helper.value.readableDatabase

            @Synchronized
            override fun close() {
                if (helper.isInitialized()) helper.value.close()
            }
        }

    companion object {
        private const val KEY = "chat-database-v1"
        private val sqliteHeader = "SQLite format 3\u0000".encodeToByteArray()
        private val preserveFile = DatabaseErrorHandler { _, failure -> throw failure }

        @Synchronized
        internal fun prepare(database: File, vault: SecretVault): ByteArray {
            System.loadLibrary("sqlcipher")
            val directory = requireNotNull(database.parentFile)
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, "${database.name}.migration.lock"), "rw").use { guard ->
                guard.channel.lock().use {
                    val plaintext = database.length() >= sqliteHeader.size &&
                        DataInputStream(database.inputStream()).use { stream ->
                            val header = ByteArray(sqliteHeader.size)
                            stream.readFully(header)
                            header.contentEquals(sqliteHeader)
                        }
                    val key = runBlocking {
                        vault.read(KEY) ?: run {
                            check(!database.exists() || database.length() == 0L || plaintext) {
                                "The database key is missing. The encrypted conversation history has been preserved."
                            }
                            val random = ByteArray(32).also(SecureRandom()::nextBytes)
                            try {
                                Base64.encode(random, Base64.NO_WRAP).also { vault.put(KEY, it) }
                            } finally {
                                random.fill(0)
                            }
                        }
                    }
                    try {
                        if (plaintext) migrate(database, key)
                        if (database.length() > 0) {
                            // A wrong key must never reach a helper that can delete a corrupt database.
                            open(database, key, SQLiteDatabase.OPEN_READONLY).use { it.version }
                        }
                        return key
                    } catch (failure: Throwable) {
                        key.fill(0)
                        throw failure
                    }
                }
            }
        }

        private fun open(file: File, key: ByteArray, flags: Int): SQLiteDatabase =
            SQLiteDatabase.openDatabase(file.absolutePath, key, null, flags, preserveFile, null)

        private fun migrate(source: File, key: ByteArray) {
            val copy = File(source.parentFile, "${source.name}.encrypting")
            fun clearStaging() {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    val part = File(copy.path + suffix)
                    check(!part.exists() || part.delete()) { "Cannot clear the unfinished encrypted copy." }
                }
            }
            clearStaging()
            try {
                val version = open(source, byteArrayOf(), SQLiteDatabase.OPEN_READWRITE).use { db ->
                    // Switching out of WAL must succeed before installing a different main file.
                    check(db.compileStatement("PRAGMA journal_mode=DELETE").use { it.simpleQueryForString() }.equals("delete", true))
                    val originalVersion = db.version
                    db.execSQL("ATTACH DATABASE ? AS sealed KEY ?", arrayOf(copy.path, key))
                    db.rawQuery("SELECT sqlcipher_export('sealed')", emptyArray<String>()).use { check(it.moveToFirst()) }
                    db.execSQL("PRAGMA sealed.user_version=$originalVersion")
                    if (db.compileStatement("SELECT COUNT(*) FROM main.sqlite_master WHERE name='sqlite_sequence'").use { it.simpleQueryForLong() } > 0) {
                        db.execSQL("DELETE FROM sealed.sqlite_sequence")
                        db.execSQL("INSERT INTO sealed.sqlite_sequence SELECT * FROM main.sqlite_sequence")
                    }
                    db.execSQL("DETACH DATABASE sealed")
                    originalVersion
                }
                open(copy, key, SQLiteDatabase.OPEN_READONLY).use { db ->
                    check(db.version == version)
                    check(db.compileStatement("PRAGMA quick_check").use { it.simpleQueryForString() } == "ok") { "The encrypted copy failed its integrity check." }
                }
                RandomAccessFile(copy, "rw").use { it.fd.sync() }
                Os.rename(copy.path, source.path)
            } finally {
                clearStaging()
            }
        }
    }
}
