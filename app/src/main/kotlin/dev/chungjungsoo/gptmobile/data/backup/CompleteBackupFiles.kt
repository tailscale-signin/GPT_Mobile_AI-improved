package dev.chungjungsoo.gptmobile.data.backup

import java.io.File
import java.util.UUID

internal class CompleteBackupFiles(roots: Map<String, File>, private val pluginRoot: File? = null) {
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

    fun replacement(staging: File, paths: Set<String>): Replacement = Replacement(staging, paths)

    inner class Replacement(private val staging: File, private val paths: Set<String>) {
        private val id = ".full-restore-${UUID.randomUUID()}"
        private val originals = mutableMapOf<File, File>()
        private val installed = mutableListOf<File>()
        private val createdDirectories = mutableListOf<File>()

        fun apply() {
            require(paths.map(::target).toSet().size == paths.size) { "Conflicting backup file locations." }

            // Restore only files explicitly selected from the archive so a partial
            // restore never deletes unrelated models, attachments or app files.
            paths.forEach { path ->
                val file = target(path)
                if (file.exists()) {
                    val root = (roots.values + listOfNotNull(pluginRoot?.canonicalFile)).first { file.toPath().startsWith(it.toPath()) }
                    val previous = File(root, "$id/${file.relativeTo(root)}")
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
            (roots.values + listOfNotNull(pluginRoot)).forEach { File(it, id).deleteRecursively() }
        }
    }

    companion object {
        fun isPluginPath(path: String): Boolean = path.startsWith("internal/plugin-installations/")
    }
}
