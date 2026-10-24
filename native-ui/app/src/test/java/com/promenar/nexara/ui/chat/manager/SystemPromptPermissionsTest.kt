package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.Test

class SystemPromptPermissionsTest {
    @Test fun `两档说明准确且均要求关键数据书面同意`() {
        ExecutionMode.entries.forEach { mode ->
            val text = SystemPromptSections.permissions(mode)
            listOf("databases/", "shared_prefs/", "no_backup/", "files/agent_skills/", "nexara_diagnostics.jsonl", "nexara_logs.txt", "diag-keep", "files/python-user", "explicit written consent", "Termux identity", "remote host", "invisible", "not constrained to the workspace").forEach {
                assertThat(text).contains(it)
            }
            if (mode == ExecutionMode.WORKSPACE) {
                assertThat(text).contains("requires user approval")
                assertThat(text).contains("If rejected, do not repeat")
                assertThat(text).doesNotContain("without per-command approval")
            } else {
                assertThat(text).contains("without per-command approval")
                assertThat(text).doesNotContain("Every shell_exec")
            }
        }
    }
}
