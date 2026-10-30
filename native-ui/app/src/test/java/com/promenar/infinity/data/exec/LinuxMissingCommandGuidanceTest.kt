package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinuxMissingCommandGuidanceTest {
    @Test fun `代理拒绝凭据且禁用交互时引导重新授权而不重试`() {
        listOf("Username", "Password").forEach { field ->
            val hint = LinuxMissingCommandGuidance.hint(128,
                "fatal: could not read $field for 'http://127.0.0.1:43123': terminal prompts disabled")
            assertThat(hint).contains("已保存的令牌")
            assertThat(hint).contains("设置 → 执行环境与连接 → Git 凭据")
            assertThat(hint).contains("重新登录 GitHub")
            assertThat(hint).contains("不要重试这条命令")
            assertThat(hint).contains("无需在 Linux 中另行登录")
        }
    }

    @Test fun `HTTPS远端缺少凭据时引导先保存再重试`() {
        val hint = LinuxMissingCommandGuidance.hint(128,
            "fatal: could not read Username for 'https://git.example.com:8443': terminal prompts disabled")
        assertThat(hint).contains("没有可用的已保存凭据")
        assertThat(hint).contains("保存该主机的凭据或登录 GitHub")
        assertThat(hint).contains("然后重试")
    }

    @Test fun `成功普通失败其它HTTP服务或已有代理中文说明不追加认证引导`() {
        val failed = "fatal: could not read Username for 'http://127.0.0.1:43123': terminal prompts disabled"
        assertThat(LinuxMissingCommandGuidance.hint(0, failed)).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(null, failed)).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(128, "fatal: repository not found")).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(128, failed.replace("127.0.0.1", "localhost"))).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(128, failed.replace("43123", "65536"))).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(128, failed.replace("http://127.0.0.1:43123", "http://other.test"))).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(128, "remote: GitHub 授权已失效，请在设置中重新登录。\n$failed")).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(128, "remote: 主机未配置，代理不转发。\nfatal: The requested URL returned error: 403")).isNull()
    }

    @Test fun `仅退出码127且确有缺少命令证据才附安装建议`() {
        assertThat(LinuxMissingCommandGuidance.hint(1, "sh: git: not found")).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(127, "permission denied")).isNull()
        assertThat(LinuxMissingCommandGuidance.hint(127, "sh: git: not found")).contains("apk add git")
        assertThat(LinuxMissingCommandGuidance.hint(127, "/bin/sh: line 1: pip: not found")).contains("apk add py3-pip")
        assertThat(LinuxMissingCommandGuidance.hint(127, "python3: command not found")).contains("apk add python3")
        assertThat(LinuxMissingCommandGuidance.hint(127, "mystery: not found")).contains("apk search")
    }

    @Test fun `支持资源占位符并提醒禁止切换执行环境`() {
        val messages = RunModeMessages { "Install with apk add %1\$s. Do not switch execution environments." }
        val hint = LinuxMissingCommandGuidance.hint(127, "sh: git: not found", messages)
        assertThat(hint).contains("apk add git")
        assertThat(hint).contains("Do not switch")
    }
}
