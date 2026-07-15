package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 契约测试：锁定 UserSettingsHome 的 Route/Content 状态提升 seam、稳定测试锚点、
 * Release 本地推理入口收敛与 Provider 操作链路。
 *
 * 采用源码扫描方式（与 AgentHubScreenContractTest 一致），不引用 Compose 运行时，
 * 仅断言源码文本内容；先以断言失败呈现真实 RED，再驱动生产实现到 GREEN。
 */
class UserSettingsHomeScreenContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    private val screenSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt",
    )
    private val uiTagsSource = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/testing/UiTags.kt",
    )

    @Test
    fun `content seam exposes state actions and content without view model or android side effects`() {
        val source = screenSource.readText()
        assertThat(source).contains("data class UserSettingsHomeScreenState(")
        assertThat(source).contains("data class UserSettingsHomeScreenActions(")
        assertThat(source).contains("fun UserSettingsHomeScreenContent(")

        val content = source.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("fun TabBar(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("SettingsViewModel")
        assertThat(content).doesNotContain("NexaraApplication")
        assertThat(content).doesNotContain("rememberLauncherForActivityResult")
        assertThat(content).doesNotContain("UCrop")
        assertThat(content).doesNotContain("LocalContext")
        assertThat(content).doesNotContain("ImageRequest.Builder")
        assertThat(content).doesNotContain("startActivity")

        val profileHeader = source.substringAfter("private fun UserProfileHeader(")
            .substringBefore("private fun AddProviderButton(")
        assertThat(profileHeader).doesNotContain("LocalContext")
        assertThat(profileHeader).doesNotContain("ImageRequest.Builder")
        assertThat(profileHeader).contains("model = avatarUri")
    }

    @Test
    fun `state carries resolved model names providers version and local inference availability`() {
        val source = screenSource.readText()
        val stateBlock = source.substringAfter("data class UserSettingsHomeScreenState(")
            .substringBefore("UserSettingsHomeScreenActions")

        assertThat(stateBlock).contains("val selectedTab")
        assertThat(stateBlock).contains("val userName")
        assertThat(stateBlock).contains("val userAvatar")
        assertThat(stateBlock).contains("val tokenCost")
        assertThat(stateBlock).contains("val language")
        assertThat(stateBlock).contains("val summaryModelName")
        assertThat(stateBlock).contains("val imageModelName")
        assertThat(stateBlock).contains("val embeddingModelName")
        assertThat(stateBlock).contains("val rerankModelName")
        assertThat(stateBlock).contains("val providers")
        assertThat(stateBlock).contains("val versionName")
        assertThat(stateBlock).contains("val localInferenceAvailable")
    }

    @Test
    fun `app settings content no longer collects or receives view model`() {
        val source = screenSource.readText()
        val appBlock = source.substringAfter("fun AppSettingsContent(").substringBefore("\n}")

        assertThat(appBlock).doesNotContain("viewModel: SettingsViewModel")
        assertThat(appBlock).doesNotContain("viewModel.providerModels")
        assertThat(appBlock).doesNotContain("viewModel.summaryModelId")
        assertThat(appBlock).doesNotContain("viewModel.imageModelId")
        assertThat(appBlock).doesNotContain("viewModel.embeddingModelId")
        assertThat(appBlock).doesNotContain("viewModel.rerankModelId")
    }

    @Test
    fun `route wires real view model state into content via state and actions`() {
        val source = screenSource.readText()
        val routeBlock = source.substringAfter("fun UserSettingsHomeScreen(").substringBefore("\n@Composable\nprivate fun AppSettingsContent")

        assertThat(routeBlock).contains("UserSettingsHomeScreenState(")
        assertThat(routeBlock).contains("UserSettingsHomeScreenActions(")
        assertThat(routeBlock).contains("UserSettingsHomeScreenContent(")
        assertThat(routeBlock).contains("BuildConfig.LOCAL_INFERENCE_AVAILABLE")
        assertThat(routeBlock).contains("BuildConfig.VERSION_NAME")
        assertThat(routeBlock).doesNotContain("val actions = remember(onNavigateToSecondary)")
        assertThat(routeBlock).contains("val actions = UserSettingsHomeScreenActions(")
        assertThat(routeBlock).contains("editingName = userName")
    }

    @Test
    fun `local inference entry is gated by state local inference availability`() {
        val source = screenSource.readText()
        assertThat(source).contains("state.localInferenceAvailable")
        assertThat(source).contains("UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY")
    }

    @Test
    fun `settings ui tags cover root tabs lists add provider card actions and local inference`() {
        val source = uiTagsSource.readText()
        assertThat(source).contains("SETTINGS_ROOT")
        assertThat(source).contains("SETTINGS_TAB_APP")
        assertThat(source).contains("SETTINGS_TAB_PROVIDER")
        assertThat(source).contains("SETTINGS_APP_LIST")
        assertThat(source).contains("SETTINGS_PROVIDER_LIST")
        assertThat(source).contains("SETTINGS_ADD_PROVIDER")
        assertThat(source).contains("SETTINGS_PROVIDER_ACTIONS")
        assertThat(source).contains("SETTINGS_LOCAL_INFERENCE_ENTRY")
        assertThat(source).contains("settingsProviderCard")
        assertThat(source).contains("settingsProviderActions")
    }

    @Test
    fun `provider card and actions tags are stable and unique per provider id`() {
        val source = uiTagsSource.readText()
        assertThat(source).contains("fun settingsProviderCard(providerId: String): String")
        assertThat(source).contains("fun settingsProviderActions(providerId: String): String")
        assertThat(source).contains("SETTINGS_PROVIDER_CARD_PREFIX")

        val screen = screenSource.readText()
        assertThat(screen).contains("UiTags.settingsProviderCard(")
        assertThat(screen).contains("UiTags.settingsProviderActions(")
        assertThat(screen).contains("settings_provider_type_generic_openai")
        assertThat(screen).contains("settings_provider_type_local")
    }
}
