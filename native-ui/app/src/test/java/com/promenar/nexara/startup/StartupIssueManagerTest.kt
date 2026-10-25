package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StartupIssueManagerTest {
    @Test fun `局部失败保留问题仅拒绝故障根的写入`() = runTest {
        val manager = StartupIssueManager()
        val scope = StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "broken", "工作区")
        manager.recover(StartupStep.WORKSPACE_JOURNAL, scope) { error("冲突") }
        assertThat(manager.issues.value).hasSize(1)
        assertThat(runCatching { manager.requireWritable(rootId = "broken") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        manager.requireWritable(rootId = "healthy")
        assertThat(manager.isRootIsolated("broken")).isTrue()
        assertThat(manager.isSessionIsolated("other")).isFalse()
    }

    @Test fun `重试成功只解除同一个问题其它步骤的隔离保留`() = runTest {
        val manager = StartupIssueManager()
        val scope = StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")
        var broken = true
        manager.recover(StartupStep.WORKSPACE_JOURNAL, scope) { check(!broken) }
        manager.recover(StartupStep.TOMBSTONES, scope) { error("冲突") }
        val issue = manager.issues.value.first { it.step == StartupStep.WORKSPACE_JOURNAL }
        broken = false
        assertThat(manager.resolve(issue.id, StartupIssueAction.RETRY)).isEqualTo(StartupIssueResolution.Resolved)
        assertThat(manager.isRootIsolated("root")).isTrue()
        assertThat(manager.issues.value).hasSize(1)
    }

    @Test fun `放弃必须明确确认且不一致时保持隔离`() = runTest {
        val manager = StartupIssueManager()
        var calls = 0
        manager.recover(StartupStep.SESSION_JOURNAL,
            StartupIssueScope(StartupScopeKind.SESSION, "session", "会话"),
            abandon = { calls++; error("布局不一致") },
        ) { error("冲突") }
        val id = manager.issues.value.single().id
        assertThat(manager.resolve(id, StartupIssueAction.ABANDON)).isEqualTo(StartupIssueResolution.ConfirmationRequired)
        assertThat(calls).isEqualTo(0)
        assertThat(manager.resolve(id, StartupIssueAction.ABANDON, confirmed = true))
            .isInstanceOf(StartupIssueResolution.Failed::class.java)
        assertThat(manager.isSessionIsolated("session")).isTrue()
    }

    @Test fun `取消不会被吞掉或记录成恢复问题`() = runTest {
        val manager = StartupIssueManager()
        val failure = runCatching {
            manager.recover(StartupStep.VECTOR_QUEUE, StartupIssueScope.subsystem(StartupSubsystem.VECTOR_QUEUE)) {
                throw CancellationException("取消")
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(manager.issues.value).isEmpty()
    }

    @Test fun `新进程管理器不继承隔离且诊断不包含异常原文`() = runTest {
        val manager = StartupIssueManager()
        manager.recover(StartupStep.TOOL_LEDGER, StartupIssueScope.subsystem(StartupSubsystem.TOOL_LEDGER)) {
            error("用户消息和 request body 私密正文")
        }
        assertThat(manager.formatIssues()).doesNotContain("私密正文")
        assertThat(StartupIssueManager().issues.value).isEmpty()
    }
    @Test fun `同一会话的实际根与异常journal声称根都保持隔离`() = runTest {
        val manager = StartupIssueManager()
        manager.bindSessionRoot("s", "actual")
        manager.bindSessionRoot("s", "journal")
        manager.recover(StartupStep.SESSION_JOURNAL, StartupIssueScope(StartupScopeKind.SESSION, "s", "会话")) { error("冲突") }
        assertThat(manager.isRootIsolated("actual")).isTrue()
        assertThat(manager.isRootIsolated("journal")).isTrue()
    }

    @Test fun `完成回调失败后仍能重新执行真实恢复`() = runTest {
        val manager = StartupIssueManager()
        var broken = true
        var callbackBroken = true
        manager.recover(StartupStep.WORKSPACE_JOURNAL, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "r", "工作区")) { check(!broken) }
        manager.onResolved = { check(!callbackBroken) }
        broken = false
        val id = manager.issues.value.single().id
        assertThat(manager.resolve(id, StartupIssueAction.RETRY)).isInstanceOf(StartupIssueResolution.Failed::class.java)
        callbackBroken = false
        assertThat(manager.resolve(id, StartupIssueAction.RETRY)).isEqualTo(StartupIssueResolution.Resolved)
    }
}
