package dev.chungjungsoo.gptmobile.data.model

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A presentation value only. Decoding a response never executes tools or establishes provenance. */
@Serializable
internal data class AnswerDataset(val version: Int, val type: String, val title: String, val columns: List<String>, val rows: List<List<String>>) {
    fun ordered(column: Int?, descending: Boolean): List<List<String>> {
        if (column == null || column !in columns.indices) return rows
        val numeric = rows.all { number(it[column]) != null }
        val comparator = if (numeric) compareBy<List<String>> { number(it[column]) } else compareBy(String.CASE_INSENSITIVE_ORDER) { row: List<String> -> row[column] }
        return rows.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    fun csv(orderedRows: List<List<String>>): String = (listOf(columns) + orderedRows).joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { cell ->
            val prefix = if (cell.trimStart().firstOrNull() in listOf('=', '+', '-', '@') && number(cell) == null) "'" else ""
            "\"" + prefix + cell.replace("\"", "\"\"") + "\""
        }
    }

    companion object {
        private val numericLiteral = Regex("""-?\d{1,24}(?:\.\d{1,12})?""")
        private val request = Regex("""(?i)\b(chart|graph|tabulate|table|plot|map|sources|diagram|flowchart|compare|comparison|metrics|counts|statistics)\b""")

        fun number(text: String): BigDecimal? = text.takeIf(numericLiteral::matches)?.toBigDecimalOrNull()

        fun parse(raw: String): AnswerDataset? {
            if (raw.length !in 2..32768 || raw.lastOrNull { !it.isWhitespace() } != '}') return null
            val data = try {
                Json.decodeFromString(serializer(), raw)
            } catch (_: SerializationException) {
                return null
            }
            fun label(text: String, max: Int) = text.isNotBlank() && text.length <= max && text.none(Char::isISOControl)
            if (data.version != 1 || data.type !in setOf("table", "bars", "metrics", "places", "sources", "diagram") || !label(data.title, 120)) return null
            if (data.columns.size !in 1..8 || data.columns.any { !label(it, 80) } || data.columns.distinct().size != data.columns.size) return null
            if (data.rows.size !in 1..64 || data.rows.any { row -> row.size != data.columns.size || row.any { it.length > 256 || it.any(Char::isISOControl) } }) return null
            if (data.type == "bars" && (data.columns.size != 2 || data.rows.any { it.first().isBlank() || (number(it.last())?.signum() ?: -1) < 0 })) return null
            if (data.type == "metrics" &&
                (
                    data.columns.size != 2 ||
                        data.rows.size > 6 ||
                        data.rows.any { !label(it[0], 64) || number(it[1]) == null } ||
                        data.rows.distinctBy { it[0] }.size != data.rows.size
                    )
            ) {
                return null
            }
            if (data.type == "places" &&
                (
                    data.rows.size > 12 ||
                        data.columns.size != 3 ||
                        data.rows.any { row ->
                            row[0].isBlank() ||
                                number(row[1])?.toDouble()?.let { it in -90.0..90.0 } != true ||
                                number(row[2])?.toDouble()?.let { it in -180.0..180.0 } != true
                        }
                    )
            ) {
                return null
            }
            if (data.type == "sources" && (data.columns.size != 2 || data.rows.any { it[0].isBlank() || sourceHost(it[1]) == null })) return null
            if (data.type == "diagram" &&
                (
                    data.columns.size != 2 ||
                        data.rows.size > 32 ||
                        data.rows.flatten().distinct().size > 16 ||
                        data.rows.distinct().size != data.rows.size ||
                        data.rows.any { edge -> edge.any { !label(it, 64) } }
                    )
            ) {
                return null
            }
            return data
        }

        fun sourceHost(value: String): String? = try {
            val uri = java.net.URI(value)
            uri.host?.takeIf { uri.scheme == "https" && uri.userInfo == null }
        } catch (_: java.net.URISyntaxException) {
            null
        }

        fun instruction(task: String, capacity: Int): String {
            if (capacity < 8192 || !request.containsMatchIn(task.take(2048))) return ""
            return "\nWhen a small comparison of known values helps and the user hasn't specified another format, you may emit one gpt-ui fenced JSON block: " +
                """{"version":1,"type":"table","title":"…","columns":["…","…"],"rows":[["…","…"]]}. """ +
                "Use metrics for up to six observed label/number pairs, or bars for nonnegative comparisons. For places use name, latitude, longitude. For sources use title, HTTPS URL. For a directed diagram use type diagram and two columns, from and to. Cite evidence in surrounding text. Never invent values."
        }
    }
}
