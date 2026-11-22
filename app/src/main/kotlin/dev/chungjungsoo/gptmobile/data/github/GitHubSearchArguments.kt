package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Closed rules carry no submitted values, so a failed precheck remains useful in diagnostics. */
internal class GitHubSearchArgumentFailure(val field: String, val rule: Rule, val expected: Expected, explanation: String) : IllegalArgumentException("$field: $explanation") {
    enum class Rule { TYPE, QUERY_LENGTH, MODE, QUERY_QUALIFIER, EXTENSION, RANGE, LINE_ORDER, PATTERN_SYNTAX, PATTERN_FUZZY, PATTERN_BOUNDS, FUZZY_IDENTIFIER }
    enum class Expected { STRING, BOOLEAN, INTEGER, NUMBER, QUERY_1_200, SEARCH_MODE, EXPLICIT_FILTERS, FILE_EXTENSION, NUMBER_0_5_1, INTEGER_0_1000000, INTEGER_1_1000000, MAX_AT_LEAST_MIN, INTEGER_0_100, TEXT_MAX_200, INTEGER_1_1000, INTEGER_1_100, LITERAL_STAR_PATTERN, FALSE, BOUNDED_PATTERN, IDENTIFIER_2_80 }

    fun reportFields(arguments: JsonObject): Map<String, Any> = buildMap {
        put("search_argument_field", field)
        put("search_argument_rule", rule.name)
        put("search_argument_expected", expected.name)
        put("search_native_action", "search_repository")
        val mode = if ("mode" !in arguments) "literal" else (arguments["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        put("search_mode", mode.takeIf { it in GitHubSearchArguments.modes } ?: "UNSUPPORTED")
        (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
            put("search_query_hash", GitHubRepositoryStateStore.digest(it))
        }
    }
}

internal object GitHubSearchArguments {
    val modes = setOf("literal", "definitions", "references", "paths", "tests", "patterns")
    private val qualifiers = Regex("(?:^|\\s)(?:extension|path):[^\\s]+")

    fun reject(field: String, rule: GitHubSearchArgumentFailure.Rule, expected: GitHubSearchArgumentFailure.Expected, explanation: String): Nothing =
        throw GitHubSearchArgumentFailure(field, rule, expected, explanation)

    fun text(args: JsonObject, key: String, fallback: String = ""): String {
        if (key !in args) return fallback
        return (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: reject(key, GitHubSearchArgumentFailure.Rule.TYPE, GitHubSearchArgumentFailure.Expected.STRING, "Expected a string.")
    }

    fun integer(args: JsonObject, key: String, fallback: Int): Int {
        if (key !in args) return fallback
        return (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: reject(key, GitHubSearchArgumentFailure.Rule.TYPE, GitHubSearchArgumentFailure.Expected.INTEGER, "Expected an integer.")
    }

    fun boolean(args: JsonObject, key: String): Boolean {
        if (key !in args) return false
        return (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: reject(key, GitHubSearchArgumentFailure.Rule.TYPE, GitHubSearchArgumentFailure.Expected.BOOLEAN, "Expected a boolean.")
    }

    fun number(args: JsonObject, key: String, fallback: Double): Double {
        if (key !in args) return fallback
        return (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: reject(key, GitHubSearchArgumentFailure.Rule.TYPE, GitHubSearchArgumentFailure.Expected.NUMBER, "Expected a number.")
    }

    fun validate(args: JsonObject): GitHubSearchOptions {
        val query = text(args, "query")
        if (query.isBlank() || query.length > 200) reject("query", GitHubSearchArgumentFailure.Rule.QUERY_LENGTH, GitHubSearchArgumentFailure.Expected.QUERY_1_200, "Use a non-blank query of at most 200 characters.")
        if (text(args, "mode", "literal") !in modes) reject("mode", GitHubSearchArgumentFailure.Rule.MODE, GitHubSearchArgumentFailure.Expected.SEARCH_MODE, "Use literal, definitions, references, paths, tests, or patterns.")
        if (qualifiers.containsMatchIn(query)) reject("query", GitHubSearchArgumentFailure.Rule.QUERY_QUALIFIER, GitHubSearchArgumentFailure.Expected.EXPLICIT_FILTERS, "Use file_extension and path_prefix instead of extension:/path: query qualifiers.")
        val extension = text(args, "file_extension").removePrefix(".")
        if (extension.isNotEmpty() && !extension.matches(Regex("[A-Za-z0-9]+"))) reject("file_extension", GitHubSearchArgumentFailure.Rule.EXTENSION, GitHubSearchArgumentFailure.Expected.FILE_EXTENSION, "Use a single extension such as kt or .kt.")
        text(args, "path_prefix")
        if (integer(args, "batch_size", 1000) !in 1..1000) reject("batch_size", GitHubSearchArgumentFailure.Rule.RANGE, GitHubSearchArgumentFailure.Expected.INTEGER_1_1000, "Use an integer from 1 to 1000.")
        if (integer(args, "max_new_blobs", 4) !in 1..100) reject("max_new_blobs", GitHubSearchArgumentFailure.Rule.RANGE, GitHubSearchArgumentFailure.Expected.INTEGER_1_100, "Use an integer from 1 to 100.")
        return GitHubSearchOptions.from(args)
    }

    fun failure(args: JsonObject): GitHubSearchArgumentFailure? = try {
        validate(args)
        null
    } catch (failure: GitHubSearchArgumentFailure) {
        failure
    }
}
