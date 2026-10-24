package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.data.termux.TermuxCommandResult
import org.junit.Test

class TermuxSettingsVisualStateTest {
    private val ready = TermuxSettingsUiState(
        enabled = true,
        settingsLoaded = true,
        availability = TermuxAvailability(true, "0.119.1", true),
    )

    @Test
    fun `未获取状态时不声明安装或权限完成`() {
        val state = TermuxSettingsUiState().visualState()
        assertThat(state.installation).isEqualTo(TermuxStepStatus.Reading)
        assertThat(state.permission).isEqualTo(TermuxStepStatus.Reading)
        assertThat(state.canRunDiagnostic).isFalse()
    }

    @Test
    fun `缺少安装或版本不支持时不允许诊断`() {
        val missing = ready.copy(availability = TermuxAvailability(false, null, false)).visualState()
        assertThat(missing.installation).isEqualTo(TermuxStepStatus.Pending)
        assertThat(missing.permission).isEqualTo(TermuxStepStatus.Pending)
        assertThat(missing.canRunDiagnostic).isFalse()

        listOf("0.108", "unknown", null).forEach { version ->
            val unsupported = ready.copy(availability = TermuxAvailability(true, version, true)).visualState()
            assertThat(unsupported.installation).isEqualTo(TermuxStepStatus.Error)
            assertThat(unsupported.canRunDiagnostic).isFalse()
        }
    }

    @Test
    fun `已有安装与权限也不能推断外部应用开关已完成`() {
        val state = ready.visualState()
        assertThat(state.installation).isEqualTo(TermuxStepStatus.Complete)
        assertThat(state.permission).isEqualTo(TermuxStepStatus.Complete)
        assertThat(state.externalApps).isEqualTo(TermuxStepStatus.Pending)
        assertThat(state.canRunDiagnostic).isTrue()
    }

    @Test
    fun `诊断仅在精确标记和成功退出时确认外部应用配置`() {
        val success = TermuxCommandResult(stdout = "NEXARA_TERMUX_OK\n", exitCode = 0)
        assertThat(ready.copy(diagnostic = success).visualState().externalApps)
            .isEqualTo(TermuxStepStatus.Complete)
        listOf(
            success.copy(stdout = "unexpected"),
            success.copy(exitCode = 1),
            success.copy(timedOut = true),
            success.copy(errorCode = "TERMUX_INTERNAL_ERROR"),
        ).forEach { result ->
            assertThat(ready.copy(diagnostic = result).visualState().externalApps)
                .isEqualTo(TermuxStepStatus.Pending)
        }
        assertThat(ready.copy(diagnostic = success.copy(errorCode = "TERMUX_EXTERNAL_APPS_DISABLED")).visualState().externalApps)
            .isEqualTo(TermuxStepStatus.Error)
    }

    @Test
    fun `集成关闭或请求占用期间保持诊断禁用条件`() {
        listOf(
            ready.copy(enabled = false),
            ready.copy(busy = true),
            ready.copy(refreshing = true),
            ready.copy(availability = TermuxAvailability(true, "0.119.1", false)),
        ).forEach { assertThat(it.visualState().canRunDiagnostic).isFalse() }
    }
}
