package com.promenar.nexara.ui.chat.runmode

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.linux.LinuxRecoveryAction
import com.promenar.nexara.data.linux.LinuxRecoveryEvent
import com.promenar.nexara.data.linux.LinuxRecoveryResult
import com.promenar.nexara.data.session.SessionRunModeCheck
import com.promenar.nexara.domain.linux.LinuxFailure
import com.promenar.nexara.domain.linux.LinuxFailureCode
import com.promenar.nexara.domain.linux.LinuxInstallProgress
import com.promenar.nexara.domain.linux.LinuxInstallStep
import com.promenar.nexara.domain.linux.LinuxInstallStepState
import com.promenar.nexara.domain.linux.LinuxMaintenance
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStepStatus
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.ui.linux.settings.linuxStepTitle
import org.junit.Test

class RunModeUiTest {
    @Test
    fun `可用会话不弹检查点，未选择与不可用分别进入选择和修复`() {
        assertThat(initialStage(SessionRunModeCheck.Available(SessionRunMode.LINUX))).isNull()
        assertThat(initialStage(SessionRunModeCheck.SessionMissing)).isNull()
        assertThat(initialStage(SessionRunModeCheck.SelectionRequired(LinuxRuntimeState.NotInstalled(0), canChooseLinux = false)))
            .isEqualTo(RunModeCheckpointStage.Select(canChooseLinux = false))
        assertThat(initialStage(SessionRunModeCheck.EnvironmentUnavailable(LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED)))))
            .isEqualTo(RunModeCheckpointStage.Unavailable(RunModeUnavailableReason.BROKEN))
    }

    @Test
    fun `环境状态映射为不可用原因`() {
        val progress = LinuxInstallProgress(emptyList(), null)
        assertThat(unavailableReason(LinuxRuntimeState.NotInstalled(0))).isEqualTo(RunModeUnavailableReason.NOT_INSTALLED)
        assertThat(unavailableReason(LinuxRuntimeState.InstallInterrupted(progress, null))).isEqualTo(RunModeUnavailableReason.INTERRUPTED)
        assertThat(unavailableReason(LinuxRuntimeState.Installing(progress))).isEqualTo(RunModeUnavailableReason.BUSY)
        assertThat(unavailableReason(LinuxRuntimeState.Maintaining(LinuxMaintenance.RESETTING))).isEqualTo(RunModeUnavailableReason.BUSY)
        assertThat(unavailableReason(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.UNSUPPORTED_ABI)))
            .isEqualTo(RunModeUnavailableReason.UNSUPPORTED)
    }

    @Test
    fun `恢复进度带出当前步骤与比例，完成事件交给调用方`() {
        val running = LinuxInstallProgress(
            listOf(
                LinuxInstallStepState(LinuxInstallStep.UNPACK, LinuxStepStatus.DONE),
                LinuxInstallStepState(LinuxInstallStep.COMMON_TOOLS, LinuxStepStatus.RUNNING),
            ),
            fraction = 0.5f,
        )

        assertThat(progressStage(LinuxRecoveryEvent.Started(LinuxRecoveryAction.RESET)))
            .isEqualTo(RunModeCheckpointStage.Working(LinuxRecoveryAction.RESET))
        assertThat(progressStage(LinuxRecoveryEvent.Progress(LinuxRecoveryAction.REPAIR, install = running)))
            .isEqualTo(RunModeCheckpointStage.Working(LinuxRecoveryAction.REPAIR, linuxStepTitle(LinuxInstallStep.COMMON_TOOLS), 0.5f))
        assertThat(progressStage(LinuxRecoveryEvent.Finished(LinuxRecoveryAction.REPAIR, LinuxRecoveryResult(false)))).isNull()
    }

    @Test
    fun `从未安装发起的修复显示为安装，其它修复仍显示修复`() {
        assertThat(workingTitle(RunModeCheckpointStage.Working(LinuxRecoveryAction.REPAIR, install = true)))
            .isEqualTo(com.promenar.nexara.R.string.run_mode_working_install)
        assertThat(workingTitle(RunModeCheckpointStage.Working(LinuxRecoveryAction.REPAIR)))
            .isEqualTo(com.promenar.nexara.R.string.run_mode_working_repair)
        assertThat(workingTitle(RunModeCheckpointStage.Working(LinuxRecoveryAction.RESET, install = true)))
            .isEqualTo(com.promenar.nexara.R.string.run_mode_working_reset)
    }
}
