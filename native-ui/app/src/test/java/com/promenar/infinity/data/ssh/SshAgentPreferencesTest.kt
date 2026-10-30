package com.promenar.infinity.data.ssh

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.BackupPreferencePolicy
import com.promenar.infinity.data.generation.BuiltinToolPreferences
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.tool.ToolArgumentApprovalRules
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.domain.tool.ToolRisk
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class SshAgentPreferencesTest {
    private fun preferences(enabled: Boolean?) = mockk<SharedPreferences>().also { prefs ->
        every { prefs.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns null
        every { prefs.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns null
        // 未保存过开关时 SharedPreferences 返回调用方传入的默认值。
        every { prefs.getBoolean(SshAgentPreferences.HOST_MANAGEMENT_KEY, any()) } answers { enabled ?: secondArg() }
    }

    @Test fun `host management tools are on by default and stay off once the user turns them off`() {
        SshAgentPreferences.HOST_MANAGEMENT_TOOLS.forEach { tool ->
            assertThat(BuiltinToolPreferences.isEnabled(preferences(null), tool)).isTrue()
            assertThat(BuiltinToolPreferences.isEnabled(preferences(true), tool)).isTrue()
            assertThat(BuiltinToolPreferences.isEnabled(preferences(false), tool)).isFalse()
        }
        assertThat(SshAgentPreferences.isHostManagementEnabled(preferences(null))).isTrue()
        assertThat(BuiltinToolPreferences.isEnabled(preferences(false), "ssh_exec")).isTrue()
    }

    @Test fun `switch is device authorization and never restored from backup`() {
        assertThat(BackupPreferencePolicy.isKnown("infinity_settings", SshAgentPreferences.HOST_MANAGEMENT_KEY)).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", SshAgentPreferences.HOST_MANAGEMENT_KEY)).isFalse()
    }

    @Test fun `approval targets bind the host alias and are never remembered`() {
        fun target(name: String, args: String, risk: ToolRisk) =
            ToolExecutionTargets.extract(ProtocolTool(function = ProtocolToolFunction(name, "", "{}"), risk = risk), args)

        assertThat(target("ssh_host_save", """{"alias":"web","hostname":"203.0.113.5","username":"deploy"}""", ToolRisk.EXTERNAL_WRITE))
            .isEqualTo(ONE_SHOT_WEB)
        assertThat(target("ssh_host_delete", """{"alias":"web"}""", ToolRisk.DELETE)).isEqualTo(ONE_SHOT_WEB)
        assertThat(target("ssh_host_install_key", """{"alias":"web"}""", ToolRisk.PROCESS)).isEqualTo(ONE_SHOT_WEB)
        assertThat(target("ssh_host_delete", """{"alias":"Bad Alias"}""", ToolRisk.DELETE)).isNull()
        assertThat(ToolExecutionTargets.display(ONE_SHOT_WEB)).isEqualTo("web")
        assertThat(ToolExecutionTargets.canRemember(ONE_SHOT_WEB)).isFalse()
    }

    @Test fun `host configuration changes need explicit approval in every execution mode`() {
        listOf("ssh_host_save", "ssh_host_delete", "ssh_host_install_key").forEach { tool ->
            assertThat(ToolArgumentApprovalRules.requiresExplicitApproval(tool, """{"alias":"web"}""")).isTrue()
        }
        assertThat(ToolArgumentApprovalRules.requiresExplicitApproval("ssh_host_list", "{}")).isFalse()
        assertThat(ToolArgumentApprovalRules.requiresExplicitApproval("delete_file", """{"path":"/a"}""")).isFalse()
        assertThat(ToolArgumentApprovalRules.requiresExplicitApproval("delete_file", """{"path":"/a","irreversible":true}""")).isFalse()
    }

    private companion object {
        const val ONE_SHOT_WEB = """["ssh_host","web",{"oneShot":true}]"""
    }
}
