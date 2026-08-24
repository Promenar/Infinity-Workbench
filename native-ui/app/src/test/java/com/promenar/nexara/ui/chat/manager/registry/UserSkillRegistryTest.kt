package com.promenar.nexara.ui.chat.manager.registry

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.repository.SkillRepository
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Test

class UserSkillRegistryTest {
    @Test
    fun `无沙箱自定义脚本即使enabled且会话选中也不广告不执行`() {
        val repository = mockk<SkillRepository>()
        coEvery { repository.getAllEnabledCustomSkills() } returns listOf(
            CustomSkillEntity("custom", "custom", "", "{\"type\":\"object\"}", "return true"),
        )
        coEvery { repository.getEnabledCustomSkillByName("custom") } returns
            CustomSkillEntity("custom", "custom", "", "{\"type\":\"object\"}", "return true")
        val registry = UserSkillRegistry(repository)

        assertThat(registry.getAllTools(listOf("custom"))).isEmpty()
        assertThat(registry.getAllSkills()).isEmpty()
        assertThat(registry.getSkill("custom")).isNull()
    }
}
