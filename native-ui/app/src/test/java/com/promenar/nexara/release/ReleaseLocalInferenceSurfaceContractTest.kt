package com.promenar.nexara.release

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ReleaseLocalInferenceSurfaceContractTest {
    @Test
    fun `设置入口和 Provider 预设均受本地推理构建能力约束`() {
        val settings = source("ui/hub/UserSettingsHomeScreen.kt")
        val providerForm = source("ui/settings/ProviderFormScreen.kt")
        val application = source("NexaraApplication.kt")
        val mainActivity = source("MainActivity.kt")
        val navGraph = source("navigation/NavGraph.kt")
        val chatViewModel = source("ui/chat/ChatViewModel.kt")
        val sessionSettings = source("ui/chat/SessionSettingsSheet.kt")

        assertThat(settings).contains("BuildConfig.LOCAL_INFERENCE_AVAILABLE")
        assertThat(providerForm).contains("availableProviderPresets")
        assertThat(providerForm).contains("localInferenceRuntimeGate.isAvailable")
        assertThat(application).contains("buildUnavailableLocalPlaceholderProvider")
        assertThat(mainActivity).contains(
            "isDebugBuild = BuildConfig.DEBUG && BuildConfig.LOCAL_INFERENCE_AVAILABLE",
        )
        assertThat(navGraph).contains("if (BuildConfig.LOCAL_INFERENCE_AVAILABLE)")
        assertThat(chatViewModel).contains("ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE")
        assertThat(chatViewModel).contains("R.string.local_inference_release_unavailable")
        assertThat(sessionSettings).contains("filterLocalInferenceModels")
    }

    private fun source(relative: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/$relative"),
    ).toString(Charsets.UTF_8)
}
