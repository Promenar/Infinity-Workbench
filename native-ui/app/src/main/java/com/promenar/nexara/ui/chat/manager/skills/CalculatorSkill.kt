package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject

class CalculatorSkill : SkillDefinition {
    override val id = "calculator"
    override val name = "calculator"
    override val description = "计算十进制数字表达式。支持 +、-、*、/、%、^、**、括号和一元正负号；^ 与 ** 均为幂运算且右结合，一元正负号优先于幂。函数：sqrt、abs、pow（2 参数）、min/max（至少 1 参数）、round（半值向正无穷取整）、floor、ceil、log/ln（自然对数）、log10（以 10 为底）、sin/cos/tan（弧度）。常量 PI、E；函数与常量均接受 Math. 前缀。不支持变量、赋值、隐式乘法、科学计数法或其它 JavaScript 语法。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema =
        """{"type":"object","properties":{"expression":{"type":"string","description":"待计算的数学表达式；函数参数用逗号分隔，语法与支持范围见工具描述"}},"required":["expression"]}"""

    override suspend fun execute(
        args: JsonObject,
        context: SkillExecutionContext
    ): ToolResult {
        val expression = args.stringArgument("expression")
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
        return parser.parse()
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c in "+-*/^%()," -> {
                    val operator = if (c == '*' && expr.getOrNull(i + 1) == '*') "**" else c.toString()
                    tokens.add(Token(operator, TokenType.OP))
                    i += operator.length
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens.add(Token(expr.substring(start, i), TokenType.NUM))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] in "_.")) i++
                    tokens.add(Token(expr.substring(start, i), TokenType.NAME))
                }
                else -> throw IllegalArgumentException("Unexpected character: $c")
            }
        }
        return tokens
    }

    private enum class TokenType { NUM, NAME, OP }

    private data class Token(val value: String, val type: TokenType)

    private class ExprParser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): Double {
            val result = parseExpression()
            require(pos == tokens.size) { "表达式末尾存在不支持的语法（位置 $pos）。" }
            return result
        }

        private fun parseExpression(): Double {
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
            while (pos < tokens.size && tokens[pos].value in listOf("*", "/", "%")) {
                val op = tokens[pos].value
                pos++
                val right = parseFactor()
                result = when (op) {
                    "*" -> result * right
                    "/" -> result / right
                    "%" -> result % right
                    else -> result
                }
            }
            return result
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            if (pos < tokens.size && tokens[pos].value in listOf("^", "**")) {
                pos++
                val exponent = parseFactor()
                return Math.pow(base, exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            if (pos < tokens.size && tokens[pos].value == "-") {
                pos++
                return -parseUnary()
            }
            if (pos < tokens.size && tokens[pos].value == "+") {
                pos++
                return parseUnary()
            }
            if (pos < tokens.size && tokens[pos].value == "(") {
                pos++
                val result = parseExpression()
                require(take(")")) { "缺少右括号（位置 $pos）。" }
                return result
            }
            if (pos < tokens.size && tokens[pos].type == TokenType.NUM) {
                return tokens[pos++].value.toDouble()
            }
            if (pos < tokens.size && tokens[pos].type == TokenType.NAME) {
                return parseName()
            }
            throw IllegalArgumentException("Unexpected token at position $pos")
        }

        private fun parseName(): Double {
            val name = tokens[pos++].value.removePrefix("Math.")
            when (name) {
                "PI" -> return Math.PI
                "E" -> return Math.E
            }
            val argumentCount = when (name) {
                "pow" -> 2
                "min", "max" -> null
                "sqrt", "abs", "round", "floor", "ceil", "log", "ln", "log10", "sin", "cos", "tan" -> 1
                else -> throw IllegalArgumentException("不支持函数或常量：$name")
            }
            require(take("(")) { "函数 $name 必须使用括号传入参数。" }
            val arguments = mutableListOf<Double>()
            if (!take(")")) {
                do {
                    arguments.add(parseExpression())
                } while (take(","))
                require(take(")")) { "函数 $name 缺少右括号（位置 $pos）。" }
            }
            require(if (argumentCount == null) arguments.isNotEmpty() else arguments.size == argumentCount) {
                if (argumentCount == null) "函数 $name 至少需要 1 个参数。" else "函数 $name 需要 $argumentCount 个参数。"
            }
            return when (name) {
                "sqrt" -> Math.sqrt(arguments[0])
                "abs" -> Math.abs(arguments[0])
                "pow" -> Math.pow(arguments[0], arguments[1])
                "min" -> arguments.minOrNull()!!
                "max" -> arguments.maxOrNull()!!
                "round" -> {
                    val lower = Math.floor(arguments[0])
                    if (arguments[0] - lower < 0.5) lower else Math.ceil(arguments[0])
                }
                "floor" -> Math.floor(arguments[0])
                "ceil" -> Math.ceil(arguments[0])
                "log", "ln" -> Math.log(arguments[0])
                "log10" -> Math.log10(arguments[0])
                "sin" -> Math.sin(arguments[0])
                "cos" -> Math.cos(arguments[0])
                "tan" -> Math.tan(arguments[0])
                else -> error("函数分派异常。")
            }
        }

        private fun take(value: String): Boolean {
            if (tokens.getOrNull(pos)?.value != value) return false
            pos++
            return true
        }
    }
}
