package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.ParameterizedSkill
import com.promenar.nexara.ui.chat.manager.SkillDefinition
import com.promenar.nexara.ui.chat.manager.SkillExecutionContext

class CalculatorSkill : SkillDefinition, ParameterizedSkill {
    override val id = "calculator"
    override val name = "calculator"
    override val description = "Evaluate a mathematical expression"
    override val mcpServerId: String? = null
    override val parametersSchema =
        """{"type":"object","properties":{"expression":{"type":"string","description":"Math expression to evaluate"}},"required":["expression"]}"""

    override suspend fun execute(
        args: Map<String, Any>,
        context: SkillExecutionContext
    ): ToolResult {
        val expression = args["expression"]?.toString()
            ?: return ToolResult(
                id = "result_${System.currentTimeMillis()}",
                content = "Missing required parameter: expression",
                status = "error"
            )
        return try {
            val result = evaluate(expression)
            ToolResult(
                id = "result_${System.currentTimeMillis()}",
                content = result.toString()
            )
        } catch (e: Exception) {
            ToolResult(
                id = "result_${System.currentTimeMillis()}",
                content = "Evaluation error: ${e.message}",
                status = "error"
            )
        }
    }

    private fun evaluate(expr: String): Double {
        val tokens = tokenize(expr)
        val parser = ExprParser(tokens)
        return parser.parseExpression()
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c in "+-*/()" -> {
                    tokens.add(Token(c.toString(), TokenType.OP))
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens.add(Token(expr.substring(start, i), TokenType.NUM))
                }
                else -> throw IllegalArgumentException("Unexpected character: $c")
            }
        }
        return tokens
    }

    private enum class TokenType { NUM, OP }

    private data class Token(val value: String, val type: TokenType)

    private class ExprParser(private val tokens: List<Token>) {
        private var pos = 0

        fun parseExpression(): Double {
            var result = parseTerm()
            while (pos < tokens.size && tokens[pos].value in listOf("+", "-")) {
                val op = tokens[pos].value
                pos++
                val right = parseTerm()
                result = if (op == "+") result + right else result - right
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parseFactor()
            while (pos < tokens.size && tokens[pos].value in listOf("*", "/")) {
                val op = tokens[pos].value
                pos++
                val right = parseFactor()
                result = if (op == "*") result * right else result / right
            }
            return result
        }

        private fun parseFactor(): Double {
            if (pos < tokens.size && tokens[pos].value == "-") {
                pos++
                return -parseFactor()
            }
            if (pos < tokens.size && tokens[pos].value == "+") {
                pos++
                return parseFactor()
            }
            if (pos < tokens.size && tokens[pos].value == "(") {
                pos++
                val result = parseExpression()
                if (pos < tokens.size && tokens[pos].value == ")") pos++
                return result
            }
            if (pos < tokens.size && tokens[pos].type == TokenType.NUM) {
                return tokens[pos++].value.toDouble()
            }
            throw IllegalArgumentException("Unexpected token at position $pos")
        }
    }
}
