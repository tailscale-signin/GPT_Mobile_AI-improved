package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded source observations. Names and call sites remain candidates, never compiler resolution. */
internal object PhoneRepositorySourceIndex {
    const val VERSION = 1
    private const val MAX_CHARACTERS = 2 * 1024 * 1024
    private const val MAX_DEFINITIONS = 2000
    private const val MAX_CALLS = 6000
    private const val MAX_IMPORTS = 1000
    private val languages = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "c", "cc", "cpp", "h", "hpp", "cs", "rs", "go", "swift")
    private val identifier = "[A-Za-z_$][A-Za-z_0-9$]*"
    private val typeDefinition = Regex("\\b(class|interface|object|struct|enum|trait|typealias)\\s+($identifier)")
    private val namedFunction = Regex("\\b(fun|def|function|fn|func)\\s+(?:<[^>\\n]{1,200}>\\s*)?(?:$identifier\\.)?($identifier)\\s*\\(")
    private val arrowFunction = Regex("\\b(?:const|let|var)\\s+($identifier)\\s*(?::[^=\\n]{1,200})?=\\s*(?:async\\s+)?(?:\\([^\\n]{0,400}\\)|$identifier)\\s*(?::[^=\\n]{1,100})?=>")
    private val variableDefinition = Regex("\\b(val|var|const|let)\\s+($identifier)")
    private val methodDefinition = Regex("^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|override|async|export|virtual|internal|open)\\s+)*(?:[A-Za-z_][A-Za-z_0-9.<>,?\\[\\]]*\\s+)?($identifier)\\s*\\([^;{}\\n]{0,500}\\)\\s*(?::[^={;\\n]{1,100})?\\s*\\{")
    private val calls = Regex("\\b($identifier)\\s*\\(")
    private val excludedCalls = setOf("if", "for", "while", "switch", "catch", "when", "synchronized", "with", "fun", "function", "def", "class", "return", "throw", "typeof", "sizeof", "assert")
    private val importLine = Regex("^\\s*(?:import|from|use|#include)\\b")
    private val scriptImportLine = Regex("^\\s*(?:(?:import|export)\\b.*\\bfrom\\s*|import\\s*)$")

    fun supports(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in languages

    fun build(path: String, original: String): JsonObject {
        val supported = supports(path)
        val source = original.take(MAX_CHARACTERS)
        val python = path.endsWith(".py", true)
        val masked = mask(source, python)
        val definitions = mutableListOf<Definition>()
        val callSites = mutableListOf<JsonObject>()
        val imports = mutableListOf<JsonObject>()
        var totalDefinitions = 0
        var totalCalls = 0
        var totalImports = 0
        var skippedLines = false
        val lineStarts = mutableListOf(0)
        source.forEachIndexed { index, c -> if (c == '\n') lineStarts += index + 1 }
        fun lineAt(offset: Int): Int {
            val result = lineStarts.binarySearch(offset)
            return if (result >= 0) result + 1 else -result - 1
        }
        val delimiterState = delimiters(masked.code)
        val delimiters = delimiterState.first
        val codeLines = masked.code.split('\n')
        if (supported) {
            codeLines.forEachIndexed { row, line ->
                if (row >= 50_000 || line.length > 8000) {
                    skippedLines = true
                    return@forEachIndexed
                }
                val offset = lineStarts[row]
                fun add(name: String, kind: String, range: IntRange) {
                    val start = offset + range.first
                    if (definitions.any { it.start == start }) return
                    totalDefinitions++
                    if (definitions.size >= MAX_DEFINITIONS) return
                    val boundary = body(masked.code, delimiters, start + name.length, kind, python, row, line, codeLines, lineStarts)
                    definitions += Definition(name, kind, row + 1, start, boundary.first, boundary.second, source.substring(offset, minOf(source.length, offset + line.length)).trim().take(240))
                }
                typeDefinition.findAll(line).forEach { match -> add(match.groupValues[2], match.groupValues[1], match.groups[2]!!.range) }
                namedFunction.findAll(line).forEach { match -> add(match.groupValues[2], "function", match.groups[2]!!.range) }
                if (!python) {
                    arrowFunction.findAll(line).forEach { match -> add(match.groupValues[1], "function", match.groups[1]!!.range) }
                    variableDefinition.findAll(line).forEach { match -> add(match.groupValues[2], "variable", match.groups[2]!!.range) }
                    methodDefinition.find(line)?.let { match ->
                        val name = match.groupValues[1]
                        if (name !in excludedCalls && !Regex("^\\s*(?:return|throw|new|case)\\b").containsMatchIn(line)) add(name, "function", match.groups[1]!!.range)
                    }
                }
            }
            val definedAt = definitions.mapTo(mutableSetOf()) { it.start }
            val enclosing = definitions.sortedBy { it.bodyStart }
            val active = mutableListOf<Definition>()
            var definitionCursor = 0
            codeLines.forEachIndexed { row, line ->
                if (row >= 50_000 || line.length > 8000) return@forEachIndexed
                val offset = lineStarts[row]
                val import = importLine.containsMatchIn(line)
                if (import) {
                    totalImports++
                    if (imports.size < MAX_IMPORTS) {
                        imports += buildJsonObject {
                            put("text", line.trim().take(1000))
                            put("line", row + 1)
                        }
                    }
                }
                // Quoted module strings are recovered only from actual import syntax in masked code.
                masked.literalsByLine[row + 1].orEmpty().firstOrNull()?.let { literal ->
                    val prefix = masked.code.substring(offset, literal.start)
                    if (scriptImportLine.matches(prefix) || Regex("^\\s*#include\\s*$").matches(prefix)) {
                        totalImports++
                        if (imports.size < MAX_IMPORTS) {
                            imports += buildJsonObject {
                                put("text", prefix.trim().take(1000))
                                put("module", literal.value.take(1000))
                                put("line", row + 1)
                            }
                        }
                    }
                }
                if (import) return@forEachIndexed
                calls.findAll(line).forEach call@{ match ->
                    val name = match.groupValues[1]
                    val start = offset + match.groups[1]!!.range.first
                    if (name in excludedCalls || start in definedAt) return@call
                    totalCalls++
                    if (callSites.size >= MAX_CALLS) return@call
                    while (definitionCursor < enclosing.size && enclosing[definitionCursor].bodyStart <= start) active += enclosing[definitionCursor++]
                    active.removeAll { it.end <= start }
                    val owner = active.maxByOrNull { it.bodyStart }
                    callSites += buildJsonObject {
                        put("name", name)
                        put("line", row + 1)
                        owner?.let { put("owner", it.key) }
                        val before = line.substring(0, match.range.first).trimEnd()
                        put("qualified_receiver", before.endsWith('.') || before.endsWith("::"))
                    }
                }
            }
        }
        return buildJsonObject {
            put("parser_version", VERSION)
            put("language", path.substringAfterLast('.', "").lowercase(Locale.ROOT))
            put("source_hash", digest(original))
            put("supported", supported)
            put("complete", supported && source.length == original.length && !skippedLines && totalDefinitions == definitions.size && totalCalls == callSites.size && totalImports == imports.size && masked.complete && delimiterState.second)
            put("compiler_resolution", false)
            put(
                "definitions",
                JsonArray(
                    definitions.map { definition ->
                        buildJsonObject {
                            put("key", definition.key)
                            put("name", definition.name)
                            put("kind", definition.kind)
                            put("line", definition.line)
                            put("end_line", lineAt(definition.end.coerceAtMost(source.length)))
                            put("excerpt", definition.excerpt)
                            definitions.asSequence().filter { it !== definition && it.bodyStart <= definition.start && definition.start < it.end }.maxByOrNull { it.bodyStart }?.let { put("parent", it.key) }
                        }
                    }
                )
            )
            put("calls", JsonArray(callSites))
            put("imports", JsonArray(imports))
            put("line_count", lineStarts.size)
        }
    }

    private data class Definition(val name: String, val kind: String, val line: Int, val start: Int, val bodyStart: Int, val end: Int, val excerpt: String) {
        val key get() = "$line:$start:$name"
    }
    private data class Literal(val start: Int, val value: String)
    private data class Masked(val code: String, val literalsByLine: Map<Int, List<Literal>>, val complete: Boolean)

    /** Preserve offsets/newlines while excluding comments, quoted literals and template interpolation. */
    private fun mask(source: String, python: Boolean): Masked {
        val result = source.toCharArray()
        val literals = mutableMapOf<Int, MutableList<Literal>>()
        var index = 0
        var line = 1
        var complete = true
        var literalCount = 0
        fun erase(until: Int) {
            while (index < until) {
                if (source[index] == '\n') line++ else result[index] = ' '
                index++
            }
        }
        while (index < source.length) {
            val c = source[index]
            when {
                c == '\n' -> {
                    line++
                    index++
                }
                python && c == '#' || !python && source.startsWith("//", index) -> erase(source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length)
                !python && source.startsWith("/*", index) -> {
                    var cursor = index + 2
                    var nesting = 1
                    while (cursor < source.length && nesting > 0) {
                        if (source.startsWith("/*", cursor)) {
                            nesting++
                            cursor += 2
                        } else if (source.startsWith("*/", cursor)) {
                            nesting--
                            cursor += 2
                        } else {
                            cursor++
                        }
                    }
                    if (nesting != 0) complete = false
                    erase(cursor)
                }
                c == '\'' || c == '"' || c == '`' -> {
                    val start = index
                    val firstLine = line
                    val triple = c != '`' && source.startsWith(c.toString().repeat(3), index)
                    val length = if (triple) 3 else 1
                    var next = index + length
                    var closed = false
                    while (next < source.length) {
                        if (source[next] == '\\') {
                            next = minOf(source.length, next + 2)
                            continue
                        }
                        if (source.startsWith(c.toString().repeat(length), next)) {
                            closed = true
                            break
                        }
                        next++
                    }
                    if (!closed) complete = false
                    val end = if (closed) next + length else source.length
                    if (!triple && c != '`' && '\n' !in source.substring(start, end) && literalCount < MAX_IMPORTS * 4) {
                        literals.getOrPut(firstLine) { mutableListOf() } += Literal(start, source.substring(start + length, next).take(1000))
                        literalCount++
                    }
                    erase(end)
                }
                else -> index++
            }
        }
        return Masked(String(result), literals, complete)
    }

    private fun delimiters(code: String): Pair<Map<Int, Int>, Boolean> {
        val pairs = mutableMapOf<Int, Int>()
        val braces = ArrayDeque<Int>()
        val parentheses = ArrayDeque<Int>()
        code.forEachIndexed { index, c ->
            if (pairs.size >= 50_000 || braces.size >= 10_000 || parentheses.size >= 10_000) return pairs to false
            when (c) {
                '{' -> braces.addLast(index)
                '}' -> if (braces.isNotEmpty()) pairs[braces.removeLast()] = index
                '(' -> parentheses.addLast(index)
                ')' -> if (parentheses.isNotEmpty()) pairs[parentheses.removeLast()] = index
            }
        }
        return pairs to (braces.isEmpty() && parentheses.isEmpty())
    }

    private fun body(code: String, pairs: Map<Int, Int>, afterName: Int, kind: String, python: Boolean, row: Int, line: String, lines: List<String>, starts: List<Int>): Pair<Int, Int> {
        if (kind == "variable") return afterName to afterName
        if (python) {
            val indent = line.takeWhile(Char::isWhitespace).replace("\t", "    ").length
            var next = row + 1
            while (next < lines.size) {
                val candidate = lines[next]
                if (candidate.isNotBlank() && candidate.takeWhile(Char::isWhitespace).replace("\t", "    ").length <= indent) break
                next++
            }
            return (starts.getOrNull(row + 1) ?: code.length) to (starts.getOrNull(next) ?: code.length)
        }
        var cursor = afterName
        val limit = minOf(code.length, afterName + 1500)
        if (kind == "function") {
            val open = code.indexOf('(', cursor).takeIf { it in cursor until limit }
            cursor = open?.let { pairs[it]?.plus(1) } ?: cursor
        }
        while (cursor < limit) {
            when (code[cursor]) {
                '{' -> return cursor to ((pairs[cursor] ?: cursor) + 1)
                ';' -> return afterName to cursor
                '=' -> return cursor to (code.indexOf('\n', cursor).takeIf { it >= 0 } ?: code.length)
                '\n' -> if (kind != "function") return afterName to cursor
            }
            cursor++
        }
        return afterName to afterName
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
