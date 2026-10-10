package dev.chungjungsoo.gptmobile.data.backup

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class CompleteBackupFiles(
    roots: Map<String, File>,
    private val pluginRoot: File? = null,
    private val transactionJournal: File? = null
) {
    private val roots = roots.mapValues { it.value.canonicalFile }
    private val excluded = setOf("datastore", "backup", "backups", "diagnostics", "amazon-product-media")

    fun collectPlugins(): Map<String, File> = buildMap {
        val root = pluginRoot?.canonicalFile ?: return@buildMap
        val registry = File(root, "native-marketplace-v1.json")
        val packages = File(root, "optional_marketplace_v1").listFiles().orEmpty()
        (listOf(registry) + packages.filter { it.name.matches(Regex("[a-z0-9-]{1,64}\\.zip")) }).filter { it.isFile }.forEach { file ->
            require(file.canonicalFile == file.absoluteFile) { "Cannot back up a symbolic link." }
            put("internal/plugin-installations/${file.relativeTo(root).invariantSeparatorsPath}", file)
        }
    }

    fun collect(): MutableMap<String, File> = buildMap {
        roots.entries.distinctBy { it.value }.forEach { (name, root) ->
            root.walkTopDown().onEnter { it == root || (it.name !in excluded && !it.name.startsWith(".full-restore-")) }
                .onFail { _, error -> throw error }
                .filter { it.isFile && it.extension != "gptbackup" }
                .forEach { file ->
                    require(file.canonicalFile == file.absoluteFile) { "Cannot back up a symbolic link." }
                    val path = "$name/${file.relativeTo(root).invariantSeparatorsPath}"
                    CompleteBackupArchive.validatePath(path)
                    put(path, file)
                }
        }
    }.toMutableMap()

    fun archivePath(path: String, sources: MutableMap<String, File>): String {
        if (path.isBlank()) return path
        val file = File(path).canonicalFile
        require(file.isFile) { "An attachment is missing. Remove the missing attachment before creating a complete backup." }
        return sources.entries.firstOrNull { it.value.canonicalFile == file }?.key ?: run {
            val name = "internal/attachments/${UUID.randomUUID()}"
            sources[name] = file
            name
        }
    }

    fun target(path: String): File {
        CompleteBackupArchive.validatePath(path)
        require(path != "database.sqlite")
        val relative = path.substringAfter('/')
        if (isPluginPath(path)) {
            val root = requireNotNull(pluginRoot) { "Plugin storage is unavailable." }.canonicalFile
            val suffix = relative.removePrefix("plugin-installations/")
            require(suffix == "native-marketplace-v1.json" || suffix.matches(Regex("optional_marketplace_v1/[a-z0-9-]{1,64}\\.zip"))) { "Invalid plugin backup file." }
            return File(root, suffix).also {
                require(it.canonicalFile == it.absoluteFile && it.toPath().startsWith(root.toPath())) { "Invalid plugin restore destination." }
            }
        }
        // Model paths are resolved by the app against its current preferred storage root.
        val root = roots.getValue(if (relative.startsWith("models/")) "external" else path.substringBefore('/'))
        return File(root, relative).also {
            require(it.canonicalFile == it.absoluteFile && it.canonicalFile.toPath().startsWith(root.toPath())) { "Invalid restore destination." }
        }
    }

    fun replacement(staging: File, paths: Set<String>, transactionId: String = UUID.randomUUID().toString()): Replacement = Replacement(staging, paths, transactionId)

    fun recoverInterruptedRestore(committedTransactionId: String?) {
        val file = transactionJournal ?: return
        if (!file.isFile) return
        val atomic = AtomicFile(file)
        val journal = atomic.openRead().bufferedReader().use { Json.decodeFromString<RestoreJournal>(it.readText()) }
        if (committedTransactionId != journal.id) {
            journal.entries.asReversed().forEach { entry ->
                val target = target(entry.path)
                val previous = previousFile(journal.id, entry.path)
                when {
                    previous.exists() -> {
                        check(!target.exists() || target.deleteRecursively()) { "Could not remove an interrupted restore file." }
                        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                        check(previous.renameTo(target)) { "Could not recover a file interrupted during restore." }
                    }
                    !entry.existedBefore && entry.started -> check(!target.exists() || target.deleteRecursively()) { "Could not remove an interrupted restore file." }
                }
            }
        }
        removeTransactionCopies(journal.id)
        atomic.delete()
    }

    inner class Replacement(private val staging: File, private val paths: Set<String>, private val id: String) {
        private val originals = mutableMapOf<File, File>()
        private val installed = mutableListOf<File>()
        private val createdDirectories = mutableListOf<File>()
        private val entries = paths.map { path -> RestoreJournalEntry(path, target(path).exists(), false) }.toMutableList()

        fun apply() {
            require(paths.map(::target).toSet().size == paths.size) { "Conflicting backup file locations." }
            persistJournal()

            // Restore only files explicitly selected from the archive so a partial
            // restore never deletes unrelated models, attachments or app files.
            paths.forEachIndexed { index, path ->
                val file = target(path)
                entries[index] = entries[index].copy(started = true)
                persistJournal()
                if (file.exists()) {
                    val previous = previousFile(id, path)
                    check(previous.parentFile!!.mkdirs() || previous.parentFile!!.isDirectory)
                    check(file.renameTo(previous)) { "Could not preserve existing app files." }
                    originals[file] = previous
                }

                createParent(file.parentFile!!)
                if (file.isDirectory && file.list()?.isEmpty() == true) check(file.delete())
                installed += file
                File(staging, path).copyTo(file, overwrite = false)
            }
        }

        fun rollback() {
            installed.asReversed().forEach { check(!it.exists() || it.deleteRecursively()) }
            createdDirectories.asReversed().forEach { check(!it.exists() || it.delete()) }
            originals.forEach { (target, previous) ->
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                check(previous.renameTo(target)) { "Could not recover original app files." }
            }
            cleanup()
        }

        private fun createParent(directory: File) {
            if (directory.isDirectory) return
            createParent(requireNotNull(directory.parentFile))
            check(directory.mkdir()) { "Could not create restored file directory." }
            createdDirectories += directory
        }

        fun cleanup() {
            removeTransactionCopies(id)
            transactionJournal?.let { AtomicFile(it).delete() }
        }

        private fun persistJournal() {
            val file = transactionJournal ?: return
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(Json.encodeToString(RestoreJournal(id, entries)).encodeToByteArray())
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
    }

    private fun previousFile(id: String, path: String): File {
        val destination = target(path)
        val root = (roots.values + listOfNotNull(pluginRoot?.canonicalFile)).first { destination.toPath().startsWith(it.toPath()) }
        return File(root, ".full-restore-$id/${destination.relativeTo(root)}")
    }

    private fun removeTransactionCopies(id: String) {
        (roots.values + listOfNotNull(pluginRoot?.canonicalFile)).distinct().forEach { File(it, ".full-restore-$id").deleteRecursively() }
    }

    @Serializable
    private data class RestoreJournal(val id: String, val entries: List<RestoreJournalEntry>)

    @Serializable
    private data class RestoreJournalEntry(val path: String, val existedBefore: Boolean, val started: Boolean)

    companion object {
        fun isPluginPath(path: String): Boolean = path.startsWith("internal/plugin-installations/")
    }
}
