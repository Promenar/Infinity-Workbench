package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import org.junit.Test

class SessionRunModeApprovalTest {
    @Test fun `Linux rootfs变更遵循命令审批范围`() {
        val target = """["path","/etc/profile",{"linuxMode":true,"linuxRootfs":true,"oneShot":true}]"""
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, "write_file", ToolRisk.FILE_WRITE, resolvedTarget = target, usesLinux = true)).isTrue()
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, "write_file", ToolRisk.FILE_WRITE, resolvedTarget = target, usesLinux = true)).isFalse()
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, "read_file", ToolRisk.SAFE_READ, resolvedTarget = target, usesLinux = true)).isFalse()
        assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
    }

    @Test fun `Linux技能路径变更在完全访问模式也逐次审批`() {
        val target = """["path","/mnt/skills/demo/SKILL.md",{"linuxMode":true,"linuxSkills":true,"oneShot":true}]"""
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, "write_file", ToolRisk.FILE_WRITE, resolvedTarget = target, usesLinux = true)).isTrue()
        assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
    }
}
