package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class StartupWriteBarrierTest {
    @After fun reset() { StartupWriteBarrier.resetForTesting() }

    @Test fun `后台恢复不能获得其它隔离根的写权限`() = runTest {
        val manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        manager.recover(StartupStep.WORKSPACE_JOURNAL, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
        var writes = 0
        manager.recover(StartupStep.BACKGROUND_JOBS, StartupIssueScope.subsystem(StartupSubsystem.BACKGROUND_JOBS)) {
            StartupWriteBarrier.requireWritable(rootId = "root")
            writes++
        }
        assertThat(writes).isEqualTo(0)
    }

    @Test fun `隔离会话的物理根不能从其它会话挂载写入`() = runTest {
        val manager = StartupIssueManager()
        manager.bindRoot("root", "/private/workspaces/root", "broken")
        manager.recover(StartupStep.SESSION_JOURNAL, StartupIssueScope(StartupScopeKind.SESSION, "broken", "会话")) { error("冲突") }
        assertThat(runCatching { manager.requireWritable(sessionId = "healthy", path = "/private/workspaces/root/file.txt") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        manager.requireWritable(sessionId = "healthy", path = "/private/workspaces/root-other/file.txt")
    }

    @Test fun `只豁免正在处置的问题且成功后解除隔离`() = runTest {
        val manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        var broken = true
        manager.recover(StartupStep.WORKSPACE_JOURNAL, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) {
            check(!broken)
            StartupWriteBarrier.requireWritable(rootId = "root")
        }
        broken = false
        assertThat(manager.resolve(manager.issues.value.single().id, StartupIssueAction.RETRY)).isEqualTo(StartupIssueResolution.Resolved)
        StartupWriteBarrier.requireWritable(rootId = "root")
    }
    @Test fun `删除入口在运行遗留journal恢复之前拒绝隔离会话`() = runTest {
        val manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        manager.recover(StartupStep.SESSION_JOURNAL, StartupIssueScope(StartupScopeKind.SESSION, "s", "会话")) { error("冲突") }
        var recoveryStarted = false
        val gate = com.promenar.nexara.data.session.SessionExecutionGate()
        assertThat(runCatching { gate.withSessionDeletionSequence("s") { recoveryStarted = true } }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(recoveryStarted).isFalse()
    }
}
