package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorSkillTest {

    private val skill = CalculatorSkill()
    private val ctx = object : SkillExecutionContext {
        override val sessionId = "test-session"
        override val agentId = "test-agent"
        override val workspacePath = null
        override val workspaceRootUuid = "root-1"
    }

    // ── 基本四则运算 ──

    @Test
    fun `addition`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2+3"), ctx)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).isEqualTo("5.0")
    }

    @Test
    fun `subtraction`() = runTest {
        val result = skill.execute(skillArgs("expression" to "10-7"), ctx)
        assertThat(result.content).isEqualTo("3.0")
    }

    @Test
    fun `multiplication`() = runTest {
        val result = skill.execute(skillArgs("expression" to "4*5"), ctx)
        assertThat(result.content).isEqualTo("20.0")
    }

    @Test
    fun `division`() = runTest {
        val result = skill.execute(skillArgs("expression" to "15/3"), ctx)
        assertThat(result.content).isEqualTo("5.0")
    }

    @Test
    fun `modulo`() = runTest {
        val result = skill.execute(skillArgs("expression" to "10%3"), ctx)
        assertThat(result.content).isEqualTo("1.0")
    }

    @Test
    fun `power`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2^3"), ctx)
        assertThat(result.content).isEqualTo("8.0")
    }

    @Test
    fun `power right-associative`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2^3^2"), ctx)
        // 3^2=9, 2^9=512
        assertThat(result.content).isEqualTo("512.0")
    }

    @Test
    fun `双星号与混合幂运算均按右结合求值`() = runTest {
        for (expression in listOf("2**3**2", "2^3**2", "2**3^2")) {
            val result = skill.execute(skillArgs("expression" to expression), ctx)
            assertThat(result.status).isEqualTo("success")
            assertThat(result.content).isEqualTo("512.0")
        }
        val fractional = skill.execute(skillArgs("expression" to "2**0.5"), ctx)
        assertThat(fractional.status).isEqualTo("success")
        assertThat(fractional.content.toDouble()).isWithin(1e-12).of(1.4142135623730951)
    }

    @Test
    fun `常用函数与Math前缀返回相同数学结果`() = runTest {
        val expressions = mapOf(
            "sqrt(9)" to 3.0,
            "abs(-2)" to 2.0,
            "pow(2, 3)" to 8.0,
            "min(3, -1, 4)" to -1.0,
            "max(3, -1, 4)" to 4.0,
            "round(1.5)" to 2.0,
            "round(-1.5)" to -1.0,
            "floor(-1.2)" to -2.0,
            "ceil(-1.2)" to -1.0,
            "log(E)" to 1.0,
            "ln(E)" to 1.0,
            "log10(100)" to 2.0,
            "sin(PI/2)" to 1.0,
            "cos(PI)" to -1.0,
            "tan(PI/4)" to 1.0,
        )
        expressions.forEach { (expression, expected) ->
            for (candidate in listOf(expression, "Math.$expression")) {
                val result = skill.execute(skillArgs("expression" to candidate), ctx)
                assertThat(result.status).isEqualTo("success")
                assertThat(result.content.toDouble()).isWithin(1e-12).of(expected)
            }
        }
    }

    @Test
    fun `round在半值临界两侧不因浮点加法提前进位`() = runTest {
        for ((expression, expected) in mapOf(
            "round(0.49999999999999994)" to 0.0,
            "Math.round(0.49999999999999994)" to 0.0,
            "round(0.5000000000000001)" to 1.0,
            "round(-0.5000000000000001)" to -1.0,
            "round(-1.5000000000000002)" to -2.0,
        )) {
            val result = skill.execute(skillArgs("expression" to expression), ctx)
            assertThat(result.status).isEqualTo("success")
            assertThat(result.content.toDouble()).isEqualTo(expected)
        }
    }

    @Test
    fun `常量与嵌套Math函数支持真机复现表达式`() = runTest {
        for ((expression, expected) in mapOf(
            "PI" to 3.141592653589793,
            "Math.PI" to 3.141592653589793,
            "E" to 2.718281828459045,
            "Math.E" to 2.718281828459045,
            "(2**10)/7 + Math.sqrt(2)*1000" to 1560.4992766588095,
            "Math.max(Math.sqrt(9), Math.pow(2, 3))" to 8.0,
        )) {
            val result = skill.execute(skillArgs("expression" to expression), ctx)
            assertThat(result.status).isEqualTo("success")
            assertThat(result.content.toDouble()).isWithin(1e-10).of(expected)
        }
    }

    @Test
    fun `模型可见中文语法说明包含运算函数常量与限制`() {
        assertThat(skill.description).contains("表达式")
        listOf("+", "-", "*", "/", "%", "^", "**", "右结合", "sqrt", "abs", "pow", "min", "max", "round", "floor", "ceil", "log", "ln", "log10", "sin", "cos", "tan", "PI", "E", "Math.", "自然对数", "弧度", "不支持").forEach {
            assertThat(skill.description).contains(it)
        }
        val expressionDescription = Json.parseToJsonElement(skill.parametersSchema).jsonObject
            .getValue("properties").jsonObject.getValue("expression").jsonObject
            .getValue("description").jsonPrimitive.content
        assertThat(expressionDescription).contains("表达式")
    }

    @Test
    fun `无效函数调用和尾随语法必须拒绝而非部分求值`() = runTest {
        for (expression in listOf("unknown(2)", "Math.random()", "sqrt()", "sqrt(1, 2)", "pow(2)", "min()", "max()", "PI(2)", "2(3)", "2 3", "(2+3", "2+3)", "sqrt(4", "pow(2,)", "2,3", "Math.PI.value")) {
            val result = skill.execute(skillArgs("expression" to expression), ctx)
            assertThat(result.status).isEqualTo("error")
        }
    }

    // ── 运算符优先级 ──

    @Test
    fun `multiplication before addition`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2+3*4"), ctx)
        assertThat(result.content).isEqualTo("14.0")
    }

    @Test
    fun `parentheses override precedence`() = runTest {
        val result = skill.execute(skillArgs("expression" to "(2+3)*4"), ctx)
        assertThat(result.content).isEqualTo("20.0")
    }

    @Test
    fun `nested parentheses`() = runTest {
        val result = skill.execute(skillArgs("expression" to "((2+3)*(4-1))"), ctx)
        assertThat(result.content).isEqualTo("15.0")
    }

    @Test
    fun `power before multiplication`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2*3^2"), ctx)
        // 3^2=9, 2*9=18
        assertThat(result.content).isEqualTo("18.0")
    }

    @Test
    fun `complex expression`() = runTest {
        val result = skill.execute(skillArgs("expression" to "3+4*2/(1-5)^2"), ctx)
        // (1-5)=-4, (-4)^2=16, 4*2=8, 8/16=0.5, 3+0.5=3.5
        assertThat(result.content).isEqualTo("3.5")
    }

    // ── 一元运算符 ──

    @Test
    fun `unary minus`() = runTest {
        val result = skill.execute(skillArgs("expression" to "-5"), ctx)
        assertThat(result.content).isEqualTo("-5.0")
    }

    @Test
    fun `unary minus with multiplication`() = runTest {
        val result = skill.execute(skillArgs("expression" to "3*-2"), ctx)
        assertThat(result.content).isEqualTo("-6.0")
    }

    @Test
    fun `double negation`() = runTest {
        val result = skill.execute(skillArgs("expression" to "--5"), ctx)
        assertThat(result.content).isEqualTo("5.0")
    }

    @Test
    fun `unary plus ignored`() = runTest {
        val result = skill.execute(skillArgs("expression" to "+5"), ctx)
        assertThat(result.content).isEqualTo("5.0")
    }

    @Test
    fun `negative parentheses`() = runTest {
        val result = skill.execute(skillArgs("expression" to "-(3+4)"), ctx)
        assertThat(result.content).isEqualTo("-7.0")
    }

    // ── 小数 ──

    @Test
    fun `decimal addition`() = runTest {
        val result = skill.execute(skillArgs("expression" to "0.1+0.2"), ctx)
        // Double 精度: 0.30000000000000004
        assertThat(result.content.toDouble()).isWithin(1e-10).of(0.3)
    }

    @Test
    fun `decimal multiplication`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2.5*4"), ctx)
        assertThat(result.content).isEqualTo("10.0")
    }

    // ── 边界与零值 ──

    @Test
    fun `zero result`() = runTest {
        val result = skill.execute(skillArgs("expression" to "5-5"), ctx)
        assertThat(result.content).isEqualTo("0.0")
    }

    @Test
    fun `division by zero returns infinity`() = runTest {
        val result = skill.execute(skillArgs("expression" to "1/0"), ctx)
        // Double 除法不为异常，结果为 Infinity
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).isEqualTo("Infinity")
    }

    @Test
    fun `large number`() = runTest {
        val result = skill.execute(skillArgs("expression" to "999999*999999"), ctx)
        assertThat(result.content).isEqualTo("9.99998000001E11")
    }

    // ── 错误处理 ──

    @Test
    fun `missing expression parameter returns error`() = runTest {
        val result = skill.execute(skillArgs(), ctx)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Missing required parameter")
    }

    @Test
    fun `invalid character returns error`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2+@"), ctx)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Unexpected character")
    }

    @Test
    fun `empty expression with spaces`() = runTest {
        val result = skill.execute(skillArgs("expression" to "   "), ctx)
        assertThat(result.status).isEqualTo("error")
    }

    @Test
    fun `trailing operator returns error`() = runTest {
        val result = skill.execute(skillArgs("expression" to "2+"), ctx)
        assertThat(result.status).isEqualTo("error")
    }

    @Test
    fun `mismatched parentheses`() = runTest {
        val result = skill.execute(skillArgs("expression" to "(2+3"), ctx)
        assertThat(result.status).isEqualTo("error")
    }
}
