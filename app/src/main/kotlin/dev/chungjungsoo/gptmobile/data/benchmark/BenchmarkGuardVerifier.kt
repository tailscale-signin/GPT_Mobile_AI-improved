package dev.chungjungsoo.gptmobile.data.benchmark

/**
 * Executes a deliberately bounded expression language for the workflow simulator.
 * It is not a Kotlin compiler or an executable-repository qualification. The complete
 * supplied function must parse, so comments/strings containing a desired fragment
 * cannot make an unrelated implementation pass.
 */
internal object BenchmarkGuardVerifier {
    fun passes(kind: String, candidate: String, allowLegacyExpression: Boolean = false): Boolean {
        if (candidate.length > 4096) return false
        val signature = when (kind) {
            "release-gate" -> Regex("^\\s*fun\\s+releaseAllowed\\s*\\(\\s*ciGreen\\s*:\\s*Boolean\\s*,\\s*approved\\s*:\\s*Boolean\\s*\\)\\s*(?::\\s*Boolean\\s*)?(?:=\\s*(.+)|\\{\\s*return\\s+(.+?)\\s*;?\\s*\\})\\s*$", RegexOption.DOT_MATCHES_ALL)
            "security-review" -> Regex("^\\s*fun\\s+elevated\\s*\\(\\s*role\\s*:\\s*String\\s*,\\s*tokenValid\\s*:\\s*Boolean\\s*\\)\\s*(?::\\s*Boolean\\s*)?(?:=\\s*(.+)|\\{\\s*return\\s+(.+?)\\s*;?\\s*\\})\\s*$", RegexOption.DOT_MATCHES_ALL)
            else -> return false
        }
        val sourceCandidate = if (allowLegacyExpression && !candidate.trimStart().startsWith("fun ")) {
            val declaration = if (kind == "release-gate") "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = " else "fun elevated(role: String, tokenValid: Boolean) = "
            declaration + candidate
        } else {
            candidate
        }
        val match = signature.matchEntire(sourceCandidate) ?: return false
        val source = match.groupValues[1].ifBlank { match.groupValues[2] }.trim().removeSuffix(";").trim()
        return runCatching {
            val expression = Parser(source).parse()
            if (kind == "release-gate") {
                listOf(false, true).all { green ->
                    listOf(false, true).all { approved -> expression(mapOf("ciGreen" to green, "approved" to approved)) == (green && approved) }
                }
            } else {
                listOf("admin", "user", "", "ADMIN").all { role ->
                    listOf(false, true).all { valid -> expression(mapOf("role" to role, "tokenValid" to valid)) == (role == "admin" && valid) }
                }
            }
        }.getOrDefault(false)
    }

    private class Parser(source: String) {
        private val tokens: List<String>
        private var index = 0
        private var depth = 0
        init {
            val token = Regex("\\s*(\\&\\&|\\|\\||==|!=|[!()]|[A-Za-z_][A-Za-z_0-9]*|\"[A-Za-z_]*\")")
            val parts = mutableListOf<String>()
            var offset = 0
            while (offset < source.length) {
                if (source.substring(offset).isBlank()) break
                val match = token.find(source, offset)
                require(match != null && match.range.first == offset)
                parts += match.groupValues[1]
                offset = match.range.last + 1
                require(parts.size <= 256)
            }
            tokens = parts
        }
        fun parse(): (Map<String, Any>) -> Boolean {
            val root = or()
            require(index == tokens.size)
            return { values -> root(values) as? Boolean ?: error("Boolean expression required") }
        }
        private fun take(value: String): Boolean = (tokens.getOrNull(index) == value).also { if (it) index++ }
        private fun or(): (Map<String, Any>) -> Any {
            var left = and()
            while (take("||")) {
                val first = left
                val right = and()
                left = { values -> (first(values) as Boolean) or (right(values) as Boolean) }
            }
            return left
        }
        private fun and(): (Map<String, Any>) -> Any {
            var left = equality()
            while (take("&&")) {
                val first = left
                val right = equality()
                left = { values -> (first(values) as Boolean) and (right(values) as Boolean) }
            }
            return left
        }
        private fun equality(): (Map<String, Any>) -> Any {
            val left = atom()
            val operator = tokens.getOrNull(index)
            if (operator != "==" && operator != "!=") return left
            index++
            val right = atom()
            return { values -> if (operator == "==") left(values) == right(values) else left(values) != right(values) }
        }
        private fun atom(): (Map<String, Any>) -> Any {
            require(++depth <= 32)
            val result: (Map<String, Any>) -> Any = when {
                take("!") -> {
                    val child = atom()
                    val negated: (Map<String, Any>) -> Any = { values -> !(child(values) as Boolean) }
                    negated
                }
                take("(") -> {
                    val child = or()
                    require(take(")"))
                    child
                }
                else -> {
                    val token = tokens.getOrNull(index++) ?: error("Missing expression")
                    when {
                        token == "true" -> { _ -> true }
                        token == "false" -> { _ -> false }
                        token.startsWith('"') -> { _ -> token.removeSurrounding("\"") }
                        token in setOf("ciGreen", "approved", "role", "tokenValid") -> { values -> requireNotNull(values[token]) }
                        else -> error("Unsupported expression")
                    }
                }
            }
            depth--
            return result
        }
    }
}
