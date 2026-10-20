package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.repository.ToolAllowlistEntry
import org.junit.Test

class ToolAllowlistRowsTest {
    private fun entry(id: String, scopeKey: String?) =
        ToolAllowlistEntry(id, "write_file", """["path","/a.md"]""", scopeKey, createdAt = 1L, sourceSessionId = null)

    @Test
    fun `作用域按前缀归类并取工作区显示名`() {
        val workspaces = listOf(WorkspaceEntity("w1", WorkspaceEntity.KIND_PROJECT, "Nexara", "nexara", 0L))
        val rows = allowlistRows(
            listOf(entry("g", null), entry("w", "workspace:w1"), entry("gone", "workspace:w9"), entry("s", "session:s1"), entry("m", "mount:m1")),
            workspaces,
        )
        assertThat(rows.map { it.scope }).containsExactly(
            AllowlistScopeLabel.Global,
            AllowlistScopeLabel.Workspace("Nexara"),
            AllowlistScopeLabel.Workspace(null),
            AllowlistScopeLabel.Session,
            AllowlistScopeLabel.Mount,
        ).inOrder()
    }
}
