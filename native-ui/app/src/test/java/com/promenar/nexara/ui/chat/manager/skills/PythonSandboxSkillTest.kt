package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.script.ScriptToolRunner
import com.promenar.nexara.domain.script.*
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class PythonSandboxSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-1"
    }

    private fun output(exit: Int, stdout: String = "", stderr: String = "") = buildJsonObject {
        put("exit_code", exit); put("stdout", stdout); put("stderr", stderr)
        put("stdout_original_length", stdout.length); put("stderr_original_length", stderr.length)
    }

    private fun parse(content: String) = Json.parseToJsonElement(content).jsonObject

    @Test fun `默认以 Python 语言、禁网与 30 秒请求运行时`() = runTest {
        var seen: ScriptRequest? = null
        val skill = PythonSandboxSkill { request, _ -> seen = request; ScriptResult(output(0, "ok\n")) }
        val result = skill.execute(buildJsonObject { put("code", "print('ok')"); put("stdin", "in") }, context)
        assertThat(seen!!.language).isEqualTo(ScriptLanguages.PYTHON)
        assertThat(seen!!.allowNetwork).isFalse()
        assertThat(seen!!.timeoutSeconds).isEqualTo(30)
        assertThat(seen!!.stdin).isEqualTo("in")
        assertThat(result.status).isEqualTo("success")
        val json = parse(result.content)
        assertThat(json["stdout"]!!.jsonPrimitive.content).isEqualTo("ok\n")
        assertThat(json["exit_code"]).isEqualTo(JsonPrimitive(0))
        assertThat(json["backend"]).isEqualTo(JsonPrimitive("sandbox"))
    }

    @Test fun `非零退出码为错误并保留 stderr 与 result`() = runTest {
        val skill = PythonSandboxSkill { _, _ ->
            ScriptResult(JsonObject(output(1, stderr = "Traceback\nValueError\n") + ("result" to JsonPrimitive(7))))
        }
        val result = skill.execute(buildJsonObject { put("code", "raise ValueError") }, context)
        assertThat(result.status).isEqualTo("error")
        val json = parse(result.content)
        assertThat(json["stderr"]!!.jsonPrimitive.content).contains("ValueError")
        assertThat(json["result"]).isEqualTo(JsonPrimitive(7))
        assertThat(json["error_code"]).isEqualTo(JsonNull)
    }

    @Test fun `超时带回已有输出与可执行指引`() = runTest {
        val skill = PythonSandboxSkill { _, _ -> ScriptResult(output(0, "partial"), errorCode = "TIMEOUT", error = "超时") }
        val json = parse(skill.execute(buildJsonObject { put("code", "while True: pass"); put("timeout_seconds", 5) }, context).content)
        assertThat(json["timed_out"]).isEqualTo(JsonPrimitive(true))
        assertThat(json["stdout"]!!.jsonPrimitive.content).isEqualTo("partial")
        assertThat(json["guidance"]!!.jsonPrimitive.content).contains("5 秒")
    }

    @Test fun `参数非法时不进入运行时`() = runTest {
        val skill = PythonSandboxSkill { _, _ -> error("不应运行") }
        listOf(
            buildJsonObject {},
            buildJsonObject { put("code", " ") },
            buildJsonObject { put("code", "x".repeat(ScriptLimits.MAX_CODE_CHARS + 1)) },
            buildJsonObject { put("code", "1"); put("stdin", "x".repeat(ScriptLimits.MAX_STDIN_CHARS + 1)) },
            buildJsonObject { put("code", "1"); put("timeout_seconds", 301) },
            buildJsonObject { put("code", "1"); put("timeout_seconds", "30") },
            buildJsonObject { put("code", "1"); put("allow_network", "true") },
            buildJsonObject { put("code", "1"); put("cwd", "/") },
        ).forEach { args ->
            val result = skill.execute(args, context)
            assertThat(result.status).isEqualTo("error")
            assertThat(parse(result.content)["error_code"]).isEqualTo(JsonPrimitive("INVALID_ARGUMENT"))
        }
    }

    @Test fun `运行时不可用给出改用其它工具的指引`() = runTest {
        val skill = PythonSandboxSkill { _, _ -> ScriptResult(errorCode = "PYTHON_UNAVAILABLE", error = "x") }
        val json = parse(skill.execute(buildJsonObject { put("code", "1") }, context).content)
        assertThat(json["guidance"]!!.jsonPrimitive.content).contains("exec_js")
    }
}
