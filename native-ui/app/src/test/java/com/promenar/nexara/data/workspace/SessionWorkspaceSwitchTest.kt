package com.promenar.nexara.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SessionWorkspaceSwitchTest {
    private val records = mapOf(
        "p" to WorkspaceEntity("p", WorkspaceEntity.KIND_PROJECT, "演示", "demo", 1),
        "s" to WorkspaceEntity("s", WorkspaceEntity.KIND_SHARED, "仓库", "/storage/emulated/0/Code/repo", 1),
    )
    private val persisted = mutableListOf<String?>()

    private fun switch(current: String?, target: String?, busy: Boolean = false, fail: Boolean = false) = runBlocking {
        switchSessionWorkspace(
            sessionId = "session",
            currentWorkspaceId = current,
            targetWorkspaceId = target,
            busy = busy,
            persist = { if (fail) error("磁盘已满") else persisted += it },
            lookup = { records[it] },
        )
    }

    @Test fun `切换写入目标并返回前后标签`() {
        val result = switch(null, "p") as WorkspaceSwitchResult.Switched
        assertThat(persisted).containsExactly("p")
        assertThat(result.from).isEqualTo(WorkspaceLabel.Private)
        assertThat(result.to).isEqualTo(WorkspaceLabel.Registered("演示", "project", "demo"))
    }

    @Test fun `切回私有工作区写入 null`() {
        val result = switch("s", null) as WorkspaceSwitchResult.Switched
        assertThat(persisted).containsExactly(null)
        assertThat(result.from).isEqualTo(WorkspaceLabel.Registered("仓库", "shared", "/storage/emulated/0/Code/repo"))
        assertThat(result.to).isEqualTo(WorkspaceLabel.Private)
    }

    @Test fun `已删除的登记可以切回私有工作区，标签标明已删除`() {
        val result = switch("gone", null) as WorkspaceSwitchResult.Switched
        assertThat(result.from).isEqualTo(WorkspaceLabel.Missing("gone"))
    }

    @Test fun `生成中拒绝切换且不写入`() {
        assertThat(switch(null, "p", busy = true)).isEqualTo(WorkspaceSwitchResult.RejectedBusy)
        assertThat(persisted).isEmpty()
    }

    @Test fun `目标与当前相同或目标登记不存在时不写入`() {
        assertThat(switch("p", "p")).isEqualTo(WorkspaceSwitchResult.Unchanged)
        assertThat(switch(null, "gone")).isInstanceOf(WorkspaceSwitchResult.Failed::class.java)
        assertThat(persisted).isEmpty()
    }

    @Test fun `持久化失败返回失败结果`() {
        val result = switch(null, "p", fail = true) as WorkspaceSwitchResult.Failed
        assertThat(result.error).hasMessageThat().contains("磁盘已满")
    }
}
