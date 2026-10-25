package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolInvocationIdentity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class StartupGuardedToolLedgerTest {
    @After fun reset() { StartupWriteBarrier.resetForTesting() }

    @Test fun `台账停用后旧审批入口和读写接口在访问底层前拒绝`() = runTest {
        val manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        manager.recover(StartupStep.TOOL_LEDGER, StartupIssueScope.subsystem(StartupSubsystem.TOOL_LEDGER)) { error("冲突") }
        val ledger = StartupGuardedToolLedger(mockk<ToolExecutionLedger>())
        val key = ToolExecutionKey("s", "m", "t")
        assertThat(runCatching { ledger.register(key, ToolInvocationIdentity("tool", "tool", "digest", "schema", false)) }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(runCatching { ledger.approve(setOf(key)) }.exceptionOrNull()).isInstanceOf(StartupIsolationException::class.java)
        assertThat(runCatching { ledger.invocationIdentity(key) }.exceptionOrNull()).isInstanceOf(StartupIsolationException::class.java)
    }

    @Test fun `只有台账本身的重试可访问底层成功后恢复正常调用`() = runTest {
        val manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        val delegate = mockk<ToolExecutionLedger>()
        coEvery { delegate.recoverApprovalState() } returns 3
        val ledger = StartupGuardedToolLedger(delegate)
        var broken = true
        manager.recover(StartupStep.TOOL_LEDGER, StartupIssueScope.subsystem(StartupSubsystem.TOOL_LEDGER)) {
            check(!broken)
            ledger.recoverApprovalState()
        }
        broken = false
        assertThat(manager.resolve(manager.issues.value.single().id, StartupIssueAction.RETRY)).isEqualTo(StartupIssueResolution.Resolved)
        assertThat(ledger.recoverApprovalState()).isEqualTo(3)
    }
}
