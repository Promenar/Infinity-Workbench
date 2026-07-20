package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class UserSettingsHomeScreenContractTest {
    private val screenSource = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt"),
    ).toString(Charsets.UTF_8)

    private val providerListSourceFile = Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderListScreen.kt")
    private val providerListSource: String
        get() = if (Files.exists(providerListSourceFile)) {
            Files.readAllBytes(providerListSourceFile).toString(Charsets.UTF_8)
        } else {
            ""
        }

    private val uiTagsSource = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `settings-home 禁止内嵌 PrimaryTabRow 与 APP-PROVIDER Tab 机制`() {
        assertThat(screenSource).doesNotContain("enum class SettingsTab")
        assertThat(screenSource).doesNotContain("PrimaryTabRow(")
        assertThat(screenSource).doesNotContain("SettingsTab(")
    }

    @Test
    fun `settings-home 不能再在主页内联 model picker 并应导航至默认模型页`() {
        assertThat(screenSource).doesNotContain("ModelPicker(")
        assertThat(screenSource).contains("DEFAULT_MODELS")
    }

    @Test
    fun `settings-home 旧 provider_form 或 provider_models 路由不应仍内嵌在主页动作中`() {
        assertThat(screenSource).doesNotContain("provider_form")
        assertThat(screenSource).doesNotContain("provider_models")
    }

    @Test
    fun `UserSettings 首页目的地应包含 theme_config 且为 provider_list 和 default_models`() {
        assertThat(screenSource).contains("THEME_CONFIG")
        assertThat(screenSource).contains("PROVIDER_LIST")
        assertThat(screenSource).contains("DEFAULT_MODELS")

        // 禁止在 UserSettings 中发明或硬编码 appearance / provider
        assertThat(screenSource).doesNotContain("onNavigateToSecondary(\"appearance\")")
        assertThat(screenSource).doesNotContain("onNavigateToSecondary(\"provider\")")
    }

    @Test
    fun `settings-home 页面布局应依次包含 profile 且按 account 和 general 和 AI-model 和 knowledge-retrieval 和 tools-data 和 about 顺序`() {
        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("private fun UserProfileListItem(")
        val orderedMarkers = listOf(
            "settings_section_account",
            "settings_section_general",
            "settings_section_ai_models",
            "settings_section_knowledge_retrieval",
            "settings_section_tools_data",
            "settings_section_about",
        )
        val indices = orderedMarkers.map(content::indexOf)

        assertThat(indices).doesNotContain(-1)
        assertThat(indices).isInStrictOrder()
        assertThat(content.substringAfter("settings_section_account").substringBefore("settings_section_general"))
            .contains("UserProfileListItem(")
        val aiSection = content.substringAfter("settings_section_ai_models")
            .substringBefore("settings_section_knowledge_retrieval")
        assertThat(aiSection.indexOf("PROVIDER_LIST")).isLessThan(aiSection.indexOf("DEFAULT_MODELS"))
    }

    @Test
    fun `settings-home 必须恰好包含一个 theme_config 和 provider_list 和 default_models 导航目的地`() {
        fun countOccurrences(sub: String): Int {
            var count = 0
            var idx = 0
            while (true) {
                idx = screenSource.indexOf(sub, idx)
                if (idx == -1) break
                count++
                idx += sub.length
            }
            return count
        }

        assertThat(countOccurrences("onNavigateToSecondary(NavDestinations.THEME_CONFIG)")).isEqualTo(1)
        assertThat(countOccurrences("onNavigateToSecondary(NavDestinations.PROVIDER_LIST)")).isEqualTo(1)
        assertThat(countOccurrences("onNavigateToSecondary(NavDestinations.DEFAULT_MODELS)")).isEqualTo(1)
    }

    @Test
    fun `app settings are grouped into continuous material lists`() {
        assertThat(screenSource).contains("NexaraSettingsSection(")
        assertThat(screenSource).contains("settings_section_account")
        assertThat(screenSource).contains("settings_section_general")
        assertThat(screenSource).contains("settings_section_ai_models")
        assertThat(screenSource).contains("settings_section_knowledge_retrieval")
        assertThat(screenSource).contains("settings_section_tools_data")
        assertThat(screenSource).contains("settings_section_about")
    }

    @Test
    fun `profile and github footer use material roles without legacy glass or hardcoded type`() {
        val profile = screenSource.substringAfter("private fun UserProfileListItem(")
            .substringBefore("internal fun GitHubProjectFooter(")
        assertThat(profile).contains("ListItem(")
        assertThat(profile).contains("containerColor = Color.Transparent")
        assertThat(profile).doesNotContain("Surface(")
        assertThat(screenSource).doesNotContain("UserProfileHeader")
        assertThat(screenSource).contains("GitHubProjectFooter")
    }

    @Test
    fun `provider screen has one material add action and a clear empty state`() {
        assertThat(providerListSource).contains("TopAppBar(")
        assertThat(providerListSource).contains("onClick = actions.onAddProvider")
        assertThat(providerListSource).contains("UiTags.SETTINGS_ADD_PROVIDER")
        assertThat(providerListSource).contains("settings_provider_empty")
        assertThat(providerListSource).contains("MaterialTheme.typography")
        assertThat(providerListSource).contains("NexaraSpacing.XLarge")
        assertThat(providerListSource).doesNotContain("NexaraTypography")
        assertThat(providerListSource).doesNotContain("NexaraColors")
    }

    @Test
    fun `provider empty state adapts to compact landscape height`() {
        assertThat(providerListSource).contains("LocalConfiguration.current.screenHeightDp")
        assertThat(providerListSource).contains("compactHeightEmptyState")
        assertThat(providerListSource).contains("ProviderEmptyState(")
    }

    @Test
    fun `provider row has one manage target and overflow owns edit and delete`() {
        assertThat(providerListSource).contains("ListItem(")
        assertThat(providerListSource).contains("Icons.Rounded.MoreVert")
        assertThat(providerListSource).contains("DropdownMenu(")
        assertThat(providerListSource).contains("DropdownMenuItem(")
        assertThat(providerListSource).contains("UiTags.settingsProviderCard(")
        assertThat(providerListSource).contains("UiTags.settingsProviderActions(")
        assertThat(providerListSource).contains("settings_provider_status_summary")
        assertThat(providerListSource).contains("settings_provider_state_enabled")
        assertThat(providerListSource).contains("settings_provider_state_disabled")
        assertThat(providerListSource).contains("settings_provider_api_key_configured")
        assertThat(providerListSource).contains("settings_provider_api_key_not_configured")
        assertThat(providerListSource).contains("settings_provider_vertex_credentials_configured")
        assertThat(providerListSource).contains("settings_provider_vertex_credentials_not_configured")
        assertThat(providerListSource).contains("settings_provider_credentials_not_required")
        assertThat(providerListSource).contains("stateDescription = providerStateDescription")
        assertThat(providerListSource).contains("val titleStyle = if (useLargeTextLayout)")
        assertThat(providerListSource).contains("maxLines = if (useLargeTextLayout) 3 else 2")
        assertThat(providerListSource).contains("TextOverflow.Ellipsis")
        assertThat(providerListSource).doesNotContain("NexaraGlassCard")
    }

    @Test
    fun `provider list is continuous and separates rows without card gaps`() {
        assertThat(providerListSource).contains("verticalArrangement = Arrangement.spacedBy(0.dp)")
        assertThat(providerListSource).contains("itemsIndexed(")
    }

    @Test
    fun `content seam exposes state actions and content without view model or android side effects`() {
        assertThat(screenSource).contains("data class UserSettingsHomeScreenState(")
        assertThat(screenSource).contains("data class UserSettingsHomeScreenActions(")
        assertThat(screenSource).contains("fun UserSettingsHomeScreenContent(")

        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("SettingsViewModel")
    }

    @Test
    fun `state carries typed summaries version and local inference availability`() {
        assertThat(screenSource).contains("val userName")
        assertThat(screenSource).contains("val userAvatar")
        assertThat(screenSource).contains("val tokenCost")
        assertThat(screenSource).contains("val language")
        assertThat(screenSource).contains("val themePreferences: NexaraThemePreferences")
        assertThat(screenSource).contains("val providerCount")
        assertThat(screenSource).contains("val configuredDefaultModelsCount")
        assertThat(screenSource).contains("val versionName")
        assertThat(screenSource).contains("val localInferenceAvailable")

        assertThat(providerListSource).contains("val providers")
    }

    @Test
    fun `app settings content no longer collects or receives view model`() {
        assertThat(screenSource).doesNotContain("viewModel: SettingsViewModel")
    }

    @Test
    fun `route wires real view model state into content via state and actions`() {
        assertThat(screenSource).contains("UserSettingsHomeScreenState(")
        assertThat(screenSource).contains("UserSettingsHomeScreenActions(")
        assertThat(screenSource).contains("UserSettingsHomeScreenContent(")
    }

    @Test
    fun `settings home observes manager state without synchronous full refresh`() {
        assertThat(screenSource).doesNotContain("viewModel.refreshProviders()")
    }

    @Test
    fun `local inference entry is gated by state local inference availability`() {
        assertThat(screenSource).contains("state.localInferenceAvailable")
        assertThat(screenSource).contains("UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY")
    }

    @Test
    fun `settings ui tags cover root tabs lists add provider card actions and local inference`() {
        assertThat(uiTagsSource).contains("SETTINGS_ROOT")
        assertThat(uiTagsSource).contains("SETTINGS_APP_LIST")
        assertThat(uiTagsSource).contains("SETTINGS_PROVIDER_LIST")
        assertThat(uiTagsSource).contains("SETTINGS_ADD_PROVIDER")
        assertThat(uiTagsSource).contains("SETTINGS_PROVIDER_ACTIONS")
        assertThat(uiTagsSource).contains("SETTINGS_LOCAL_INFERENCE_ENTRY")
    }

    @Test
    fun `provider card and actions tags are stable and unique per provider id`() {
        assertThat(uiTagsSource).contains("fun settingsProviderCard(providerId: String): String")
        assertThat(uiTagsSource).contains("fun settingsProviderActions(providerId: String): String")

        assertThat(providerListSource).contains("UiTags.settingsProviderCard(")
        assertThat(providerListSource).contains("UiTags.settingsProviderActions(")
    }
}
