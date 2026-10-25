package com.promenar.nexara.data.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinuxMissingCommandGuidanceTest {
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
