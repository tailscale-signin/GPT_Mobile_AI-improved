package dev.chungjungsoo.gptmobile.data.backup

import java.io.File
import java.util.UUID

internal class CompleteBackupFiles(roots: Map<String, File>) {
    private val roots = roots.mapValues { it.value.canonicalFile }
    private val excluded = setOf("datastore", "backup", "backups")

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
        // Model paths are resolved by the app against its current preferred storage root.
        val root = roots.getValue(if (relative.startsWith("models/")) "external" else path.substringBefore('/'))
        return File(root, relative).also {
            require(it.canonicalFile == it.absoluteFile && it.canonicalFile.toPath().startsWith(root.toPath())) { "Invalid restore destination." }
        }
    }

    fun replacement(staging: File, paths: Set<String>): Replacement = Replacement(staging, paths)

    inner class Replacement(private val staging: File, private val paths: Set<String>) {
        private val id = ".full-restore-${UUID.randomUUID()}"
        private val originals = mutableMapOf<File, File>()
        private val installed = mutableListOf<File>()
        private val createdDirectories = mutableListOf<File>()

        fun apply() {
            require(paths.map(::target).toSet().size == paths.size) { "Conflicting backup file locations." }
            collect().values.distinct().forEach { current ->
                val root = roots.values.first { current.toPath().startsWith(it.toPath()) }
                val previous = File(root, "$id/${current.relativeTo(root)}")
                check(previous.parentFile!!.mkdirs() || previous.parentFile!!.isDirectory)
                check(current.renameTo(previous)) { "Could not preserve existing app files." }
                originals[current] = previous
            }
            paths.forEach { path ->
                val file = target(path)
                createParent(file.parentFile!!)
                if (file.isDirectory && file.list()?.isEmpty() == true) check(file.delete())
                installed += file
                File(staging, path).copyTo(file, overwrite = false)
            }
        }

        fun rollback() {
            installed.asReversed().forEach { check(!it.exists() || it.delete()) }
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
            roots.values.forEach { File(it, id).deleteRecursively() }
        }
    }
}
