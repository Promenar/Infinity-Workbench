package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.testing.UiTags
import java.io.File
import org.junit.Test

class ChatE2eSeamContractTest {
    private val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    @Test
    fun `提供商切换E2E具有真实控件锚点和Coordinator注入边界`() {
        val screen = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
        ).readText()
        val settings = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/SessionSettingsSheet.kt",
        ).readText()
        val route = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatRoute.kt",
        ).readText()

        assertThat(screen).contains("UiTags.CHAT_OPTIONS")
        assertThat(screen).contains("UiTags.CHAT_SESSION_SETTINGS")
        assertThat(screen).contains("UiTags.CHAT_INPUT")
        assertThat(settings).contains("UiTags.CHAT_MODEL_LIST")
        assertThat(settings).contains("UiTags.chatModelOption(model.id)")
        assertThat(route).contains("fun withGenerationCoordinator(")
        assertThat(route).contains("generationCoordinatorOverride = generationCoordinator")
        assertThat(route).doesNotContain("Registry")
    }

    @Test
    fun `动态模型锚点保留完整稳定ID`() {
        assertThat(UiTags.chatModelOption("provider-a::model/x"))
            .isEqualTo("chat_model_option:provider-a::model/x")
    }
}
