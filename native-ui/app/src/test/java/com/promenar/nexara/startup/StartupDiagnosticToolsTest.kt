package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.tool.ToolArgumentApprovalRules
import com.promenar.nexara.domain.tool.ToolExecutionPolicy
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.startup.diagnostics.AppDiagnostics
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupDiagnosticToolsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `诊断读取返回脱敏问题与近期记录且不改变隔离`() = runTest {
        AppDiagnostics.initialize(temporaryFolder.newFolder())
        AppDiagnostics.recordError("startup-test", IllegalStateException("移动类 journal 文件布局冲突"))
        val manager = StartupIssueManager()
        manager.recover(StartupStep.WORKSPACE_JOURNAL, rootScope) { error("私密用户消息正文") }
        val tool = startupDiagnosticTools(manager).single { it.name == "app_diagnostics" }

        val result = tool.execute(JsonObject(emptyMap()), context())

        assertThat(tool.risk).isEqualTo(ToolRisk.SAFE_READ)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains(manager.issues.value.single().id)
        assertThat(result.content).contains("移动类 journal 文件布局冲突")
        assertThat(result.content).doesNotContain("私密用户消息正文")
        assertThat(manager.isRootIsolated("broken-root")).isTrue()
    }

    @Test
    fun `处置工具不能用参数中的确认绕过底座审批`() = runTest {
        val manager = StartupIssueManager()
        var retryCalls = 0
        manager.recover(StartupStep.WORKSPACE_JOURNAL, rootScope) { retryCalls++; error("冲突") }
        val id = manager.issues.value.single().id
        val tool = startupDiagnosticTools(manager).single { it.name == "resolve_startup_issue" }

        val result = tool.execute(args("""{"issueId":"$id","action":"retry","confirmed":true}"""), context())

        assertThat(result.status).isEqualTo("error")
        assertThat(retryCalls).isEqualTo(1)
        assertThat(manager.isRootIsolated("broken-root")).isTrue()
    }

    @Test
    fun `批准的重试可以解除指定范围隔离`() = runTest {
        val manager = StartupIssueManager()
        var broken = true
        manager.recover(StartupStep.WORKSPACE_JOURNAL, rootScope) { check(!broken) }
        val id = manager.issues.value.single().id
        broken = false

        val result = startupDiagnosticTools(manager).single { it.name == "resolve_startup_issue" }
            .execute(args("""{"issueId":"$id","action":"retry"}"""), context(approved = true))

        assertThat(result.status).isEqualTo("success")
        assertThat(manager.issues.value).isEmpty()
        assertThat(manager.isRootIsolated("broken-root")).isFalse()
    }

    @Test
    fun `逐次审批同时充当放弃未完成操作的明确确认`() = runTest {
        val manager = StartupIssueManager()
        var abandoned = false
        manager.recover(StartupStep.WORKSPACE_JOURNAL, rootScope, abandon = { abandoned = true }) { error("冲突") }
        val id = manager.issues.value.single().id

        val result = startupDiagnosticTools(manager).single { it.name == "resolve_startup_issue" }
            .execute(args("""{"issueId":"$id","action":"abandon"}"""), context(approved = true))

        assertThat(result.status).isEqualTo("success")
        assertThat(abandoned).isTrue()
        assertThat(manager.issues.value).isEmpty()
    }

    @Test
    fun `无效动作不会执行恢复且不存在问题返回失败`() = runTest {
        val manager = StartupIssueManager()
        var retries = 0
        manager.recover(StartupStep.WORKSPACE_JOURNAL, rootScope) { retries++; error("冲突") }
        val id = manager.issues.value.single().id
        val tool = startupDiagnosticTools(manager).single { it.name == "resolve_startup_issue" }

        assertThat(tool.execute(args("""{"issueId":"$id","action":"delete"}"""), context(true)).status).isEqualTo("error")
        assertThat(tool.execute(args("""{"issueId":"missing","action":"retry"}"""), context(true)).status).isEqualTo("error")
        assertThat(retries).isEqualTo(1)
        assertThat(manager.issues.value).hasSize(1)
    }

    @Test
    fun `所有运行模式和已解析目标均不能豁免逐次处置审批`() {
        for (mode in ExecutionMode.entries) {
            assertThat(ToolArgumentApprovalRules.requiresExplicitApproval("resolve_startup_issue", "{}", "remembered-target")).isTrue()
            assertThat(ToolExecutionPolicy.requiresApproval(mode, "resolve_startup_issue", ToolRisk.SAFE_READ,
                """{"issueId":"issue","action":"retry"}""", resolvedTarget = "remembered-target")).isTrue()
        }
    }

    private fun args(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun context(approved: Boolean = false) = object : SkillExecutionContext {
        override val sessionId = "diagnostic-session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "diagnostic-root"
        override val isApprovalBoundExecution = approved
    }

    private val rootScope = StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "broken-root", "工作区")
}
