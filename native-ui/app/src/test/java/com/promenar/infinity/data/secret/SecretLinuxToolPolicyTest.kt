package com.promenar.infinity.data.secret

import com.promenar.infinity.data.generation.DefaultSessionToolResolver
import com.promenar.infinity.data.generation.SessionRunModeToolDefinitions
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.tool.*
import org.junit.Assert.*
import org.junit.Test

class SecretLinuxToolPolicyTest {
    private val python = ProtocolTool(function = ProtocolToolFunction("python_exec", "内置 Python", """{"type":"object"}"""), runtimeToolId = "python_exec", risk = ToolRisk.PROCESS)

    @Test fun `Linux Python工具可见且描述与审批目标属于Linux`() {
        assertFalse(DefaultSessionToolResolver.LINUX_HIDDEN_TOOLS.contains("python_exec"))
        val prepared = SessionRunModeToolDefinitions.forSession(python, SessionRunMode.LINUX)
        assertTrue(prepared.function.description.contains("Linux"))
        assertEquals(CommandToolBackend.LINUX, CommandToolClassification.backendFor("python_exec", usesLinux = true))
        val target = ToolExecutionTargets.extract(prepared, """{"code":"print('ok')"}""", usesLinux = true)!!
        assertTrue(ToolExecutionTargets.runsInLinux(target))
        assertFalse(target.contains("export "))
        assertTrue(target.contains("print('ok')"))
    }

    @Test fun `内置Python与保密交互工具的审批类型不变`() {
        assertEquals(CommandToolBackend.BUILTIN_PYTHON, CommandToolClassification.backendFor("python_exec"))
        assertNull(CommandToolClassification.backendFor("request_secret", usesLinux = true))
        assertNull(CommandToolClassification.backendFor("show_secret", usesLinux = true))
    }
}
