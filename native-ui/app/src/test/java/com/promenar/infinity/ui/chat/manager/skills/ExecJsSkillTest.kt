package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.script.ScriptToolRunner
import com.promenar.infinity.domain.script.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class ExecJsSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-1"
    }
    private val runner = ScriptToolRunner { request, _ -> ScriptResult(JsonPrimitive(request.code.length)) }

    @Test fun `缺少代码或超过字符上限均不进入运行时`() = runTest {
        val skill = ExecJsSkill(runner)
        assertThat(skill.execute(buildJsonObject {}, context).status).isEqualTo("error")
        assertThat(skill.execute(buildJsonObject { put("code", "x".repeat(50001)) }, context).status).isEqualTo("error")
        assertThat(skill.execute(buildJsonObject { put("code", "x".repeat(50000)) }, context).content).isEqualTo("Result: 50000")
    }

    @Test fun `默认禁网30秒且合法选项原样传入`() = runTest {
        val skill = ExecJsSkill { request, _ ->
            assertThat(request.allowNetwork).isFalse()
            assertThat(request.timeoutSeconds).isEqualTo(30)
            ScriptResult(JsonPrimitive("中 C:\\tmp \"q\""))
        }
        assertThat(skill.execute(buildJsonObject { put("code", "1") }, context).content).contains("中")
        val enabled = ExecJsSkill { request, _ ->
            assertThat(request.allowNetwork).isTrue()
            assertThat(request.timeoutSeconds).isEqualTo(300)
            ScriptResult()
        }
        assertThat(enabled.execute(buildJsonObject { put("code", "1"); put("allow_network", true); put("timeout_seconds", 300) }, context).status).isEqualTo("success")
        for (seconds in listOf(0, 301)) assertThat(enabled.execute(buildJsonObject { put("code", "1"); put("timeout_seconds", seconds) }, context).status).isEqualTo("error")
    }

    @Test fun `脚本文件参数规范化且保留args`() = runTest {
        val skill = RunScriptSkill { request, _ ->
            assertThat(request.entryPath).isEqualTo("/mnt/demo/main.mjs")
            assertThat(request.args).isEqualTo(buildJsonArray { add("a") })
            ScriptResult()
        }
        assertThat(skill.execute(buildJsonObject { put("path", "mnt/demo//main.mjs"); put("args", buildJsonArray { add("a") }) }, context).status).isEqualTo("success")
        assertThat(skill.execute(buildJsonObject { put("path", "/../a.js") }, context).status).isEqualTo("error")
        assertThat(skill.execute(buildJsonObject { put("path", "/a.py") }, context).status).isEqualTo("error")
    }

    @Test fun `运行时失败明确返回且协程取消不被吞掉`() = runTest {
        val failing = ExecJsSkill { _, _ -> ScriptResult(errorCode = "MEMORY_LIMIT", error = "内存超限") }
        assertThat(failing.execute(buildJsonObject { put("code", "1") }, context).content).contains("MEMORY_LIMIT")
        val cancelled = ExecJsSkill { _, _ -> throw CancellationException("用户取消") }
        try { cancelled.execute(buildJsonObject { put("code", "1") }, context); error("应传播取消") }
        catch (_: CancellationException) { }
    }
}
