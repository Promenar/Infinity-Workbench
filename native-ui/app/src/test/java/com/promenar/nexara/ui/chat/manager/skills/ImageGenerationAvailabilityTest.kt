package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.DefaultSessionToolResolver
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class ImageGenerationAvailabilityTest {
    private val imageModel = MutableStateFlow("")
    private val providers = mockk<ProviderManager> {
        every { imageModelId } returns imageModel
    }
    private val skill = ImageGenerationSkill(mockk<Context>(), providers)
    private val registry = DefaultSkillRegistry().apply { register(skill) }

    @Test
    fun `未选择图像模型不广告工具但保留登记与设置可见的技能列表`() {
        assertThat(registry.getAllTools(null)).isEmpty()
        assertThat(registry.getAllTools(listOf("image_generation"))).isEmpty()
        assertThat(registry.getAllSkills()).containsExactly(skill)
        assertThat(registry.getSkill("generate_image")).isSameInstanceAs(skill)
    }

    @Test
    fun `同一会话解析器随图像配置清除与恢复即时更新工具`() {
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet("enabled_skills", null) } returns null
        every { settings.getStringSet("known_builtin_skills", null) } returns null
        val resolver = DefaultSessionToolResolver(settings, registry)
        val session = Session("image-session", "agent")

        assertThat(resolver.resolve(session)).isEmpty()
        imageModel.value = "image-model"
        assertThat(resolver.resolve(session).map { it.function.name }).containsExactly("generate_image")
        imageModel.value = "  "
        assertThat(resolver.resolve(session)).isEmpty()
        imageModel.value = "image-model-restored"
        assertThat(resolver.resolve(session).map { it.function.name }).containsExactly("generate_image")
    }
}
