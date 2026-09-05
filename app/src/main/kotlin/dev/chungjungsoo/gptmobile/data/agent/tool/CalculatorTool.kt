package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CalculatorTool : AgentTool {
    override val definition = AgentToolDefinition(
        name = BuiltInAgentTool.CALCULATE_EXPRESSION,
        description = "Evaluates a mathematical expression safely and accurately. Supports arithmetic (+, -, *, /, %, ^), standard functions (sqrt, cbrt, sin, cos, tan, asin, acos, atan, sinh, cosh, tanh, log, ln, log2, abs, round, floor, ceil, min, max, pow), and constants (pi, e).",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "expression",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "The mathematical expression to evaluate, e.g. '(12 + 8) * 3 / 2', 'sqrt(144) + 2^4', 'sin(pi / 2)'")
                        }
                    )
                }
            )
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("expression"))
                }
            )
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val expression = arguments["expression"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (expression.isBlank()) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Json(
                    buildJsonObject {
                        put("error", "Expression parameter must not be empty.")
                    }
                ),
                isError = true
            )
        }

        return try {
            val result = evaluate(expression)
            val formatted = if (result % 1.0 == 0.0 && !result.isInfinite() && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) {
                result.toLong().toString()
            } else {
                result.toString()
            }
            AgentToolResult(
                callId = callId,
                content = ToolResultContent.Json(
                    buildJsonObject {
                        put("expression", expression)
                        put("result", result)
                        put("formatted", formatted)
                    }
                ),
                isError = false
            )
        } catch (e: Exception) {
            AgentToolResult(
                callId = callId,
                content = ToolResultContent.Json(
                    buildJsonObject {
                        put("expression", expression)
                        put("error", e.message ?: "Failed to evaluate expression")
                    }
                ),
                isError = true
            )
        }
    }

    companion object {
        fun evaluate(expr: String): Double {
            val parser = ExpressionParser(expr)
            val value = parser.parseExpression()
            if (parser.hasMore()) {
                throw IllegalArgumentException("Unexpected character '${parser.peek()}' at position ${parser.position}")
            }
            return value
        }
    }

    private class ExpressionParser(private val text: String) {
        var position = 0

        fun hasMore(): Boolean {
            skipWhitespace()
            return position < text.length
        }

        fun peek(): Char = text[position]

        private fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) {
                position++
            }
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                if (position >= text.length) break
                when (text[position]) {
                    '+' -> {
                        position++
                        value += parseTerm()
                    }
                    '-' -> {
                        position++
                        value -= parseTerm()
                    }
                    else -> break
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                if (position >= text.length) break
                when (text[position]) {
                    '*' -> {
                        position++
                        value *= parseFactor()
                    }
                    '/' -> {
                        position++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        value /= divisor
                    }
                    '%' -> {
                        position++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Modulo by zero")
                        value %= divisor
                    }
                    else -> break
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            skipWhitespace()
            if (position < text.length && text[position] == '^') {
                position++
                val exponent = parseFactor()
                return base.pow(exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            if (position >= text.length) throw IllegalArgumentException("Unexpected end of expression")
            if (text[position] == '+') {
                position++
                return parseUnary()
            }
            if (text[position] == '-') {
                position++
                return -parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            if (position >= text.length) throw IllegalArgumentException("Unexpected end of expression")

            val c = text[position]
            if (c == '(') {
                position++
                val value = parseExpression()
                skipWhitespace()
                if (position >= text.length || text[position] != ')') {
                    throw IllegalArgumentException("Missing closing parenthesis ')'")
                }
                position++
                return value
            }

            if (c.isDigit() || c == '.') {
                val start = position
                var hasDot = false
                while (position < text.length) {
                    val curr = text[position]
                    if (curr.isDigit()) {
                        position++
                    } else if (curr == '.' && !hasDot) {
                        hasDot = true
                        position++
                    } else {
                        break
                    }
                }
                val token = text.substring(start, position)
                return token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $token")
            }

            if (c.isLetter()) {
                val start = position
                while (position < text.length && text[position].isLetter()) {
                    position++
                }
                val name = text.substring(start, position).lowercase()

                // Check constants first
                when (name) {
                    "pi" -> return Math.PI
                    "e" -> return Math.E
                }

                // Otherwise, expects a function call with parentheses
                skipWhitespace()
                if (position >= text.length || text[position] != '(') {
                    throw IllegalArgumentException("Expected '(' after function '$name'")
                }
                position++ // skip '('

                val args = mutableListOf<Double>()
                skipWhitespace()
                if (position < text.length && text[position] != ')') {
                    while (true) {
                        args.add(parseExpression())
                        skipWhitespace()
                        if (position < text.length && text[position] == ',') {
                            position++ // skip ','
                        } else {
                            break
                        }
                    }
                }
                skipWhitespace()
                if (position >= text.length || text[position] != ')') {
                    throw IllegalArgumentException("Missing closing parenthesis for function '$name'")
                }
                position++ // skip ')'

                return evaluateFunction(name, args)
            }

            throw IllegalArgumentException("Unexpected character '$c' at position $position")
        }

        private fun evaluateFunction(name: String, args: List<Double>): Double {
            fun requireArgs(count: Int) {
                if (args.size != count) {
                    throw IllegalArgumentException("Function '$name' expects $count argument(s), got ${args.size}")
                }
            }

            return when (name) {
                "sqrt" -> {
                    requireArgs(1)
                    if (args[0] < 0) throw ArithmeticException("Square root of negative number: ${args[0]}")
                    sqrt(args[0])
                }
                "cbrt" -> {
                    requireArgs(1)
                    cbrt(args[0])
                }
                "abs" -> {
                    requireArgs(1)
                    kotlin.math.abs(args[0])
                }
                "round" -> {
                    requireArgs(1)
                    round(args[0])
                }
                "floor" -> {
                    requireArgs(1)
                    floor(args[0])
                }
                "ceil" -> {
                    requireArgs(1)
                    ceil(args[0])
                }
                "sin" -> {
                    requireArgs(1)
                    sin(args[0])
                }
                "cos" -> {
                    requireArgs(1)
                    cos(args[0])
                }
                "tan" -> {
                    requireArgs(1)
                    tan(args[0])
                }
                "asin" -> {
                    requireArgs(1)
                    asin(args[0])
                }
                "acos" -> {
                    requireArgs(1)
                    acos(args[0])
                }
                "atan" -> {
                    requireArgs(1)
                    atan(args[0])
                }
                "sinh" -> {
                    requireArgs(1)
                    sinh(args[0])
                }
                "cosh" -> {
                    requireArgs(1)
                    cosh(args[0])
                }
                "tanh" -> {
                    requireArgs(1)
                    tanh(args[0])
                }
                "ln" -> {
                    requireArgs(1)
                    if (args[0] <= 0) throw ArithmeticException("Logarithm of non-positive number: ${args[0]}")
                    ln(args[0])
                }
                "log" -> {
                    if (args.size == 1) {
                        if (args[0] <= 0) throw ArithmeticException("Logarithm of non-positive number: ${args[0]}")
                        log10(args[0])
                    } else if (args.size == 2) {
                        if (args[0] <= 0 || args[1] <= 0 || args[1] == 1.0) {
                            throw ArithmeticException("Invalid logarithm base or argument")
                        }
                        ln(args[0]) / ln(args[1])
                    } else {
                        throw IllegalArgumentException("Function 'log' expects 1 or 2 arguments, got ${args.size}")
                    }
                }
                "log2" -> {
                    requireArgs(1)
                    if (args[0] <= 0) throw ArithmeticException("Logarithm of non-positive number: ${args[0]}")
                    log2(args[0])
                }
                "min" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("Function 'min' requires at least 1 argument")
                    args.reduce { acc, d -> min(acc, d) }
                }
                "max" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("Function 'max' requires at least 1 argument")
                    args.reduce { acc, d -> max(acc, d) }
                }
                "pow" -> {
                    requireArgs(2)
                    args[0].pow(args[1])
                }
                else -> throw IllegalArgumentException("Unknown function: $name")
            }
        }
    }
}
