package com.promenar.infinity.data.agent

import com.promenar.infinity.data.local.db.dao.AgentDao
import com.promenar.infinity.data.local.db.entity.AgentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PresetAgentAvatarUpgradePersistenceTest {
    @Test
    fun `升级只写匹配行且第二次执行不写入`() = runTest {
        val dao = mockk<AgentDao>()
        var current = AgentEntity("default", "默认", icon = "✨\uFE0F", color = "#c0c1ff", createdAt = 1)
        coEvery { dao.getById("default") } answers { current }
        coEvery { dao.getById("coder") } returns null
        coEvery { dao.getById("writer") } returns AgentEntity("writer", "写作", icon = "custom", color = "#10B981", createdAt = 1)
        coEvery { dao.upgradePresetAvatarIfUnchanged(any(), any(), any(), any(), any()) } answers {
            current = current.copy(icon = arg(3), color = arg(4))
            1
        }
        upgradePresetAgentAvatars(dao)
        upgradePresetAgentAvatars(dao)
        coVerify(exactly = 1) { dao.upgradePresetAvatarIfUnchanged("default", "✨\uFE0F", "#c0c1ff", "sym:all_inclusive", "#6366F1") }
        coVerify(exactly = 1) { dao.upgradePresetAvatarIfUnchanged(any(), any(), any(), any(), any()) }
    }
}
