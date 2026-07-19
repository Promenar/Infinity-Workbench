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

    private fun sourceBlock(start: String, end: String): String =
        screenSource.readText().substringAfter(start).substringBefore(end)

    @Test
    fun `app shell uses material small top bar and primary tab row`() {
        val content = sourceBlock(
            start = "internal fun UserSettingsHomeScreenContent(",
            end = "internal fun TabBar(",
        )
        val tabs = sourceBlock(
            start = "internal fun TabBar(",
            end = "private fun AppSettingsContent(",
        )

        assertThat(content).contains("TopAppBar(")
        assertThat(content).contains("MaterialTheme.typography.titleLarge")
        assertThat(content).contains("MaterialTheme.colorScheme.surface")
        assertThat(content).contains("NexaraSpacing.ScreenHorizontal")
        assertThat(content).doesNotContain("NexaraColors.CanvasBackground.copy")
        assertThat(content).doesNotContain("NexaraTypography.headlineLarge")
        assertThat(content).doesNotContain("padding(horizontal = 20.dp)")

        assertThat(tabs).contains("PrimaryTabRow(")
        assertThat(tabs).contains("Tab(")
        assertThat(tabs).contains("MaterialTheme.colorScheme")
        assertThat(tabs).doesNotContain("animateColorAsState")
        assertThat(tabs).doesNotContain("height(2.dp)")
        assertThat(tabs).doesNotContain("NexaraColors")
    }

    @Test
    fun `app settings are grouped into continuous material lists`() {
        val app = sourceBlock(
            start = "private fun AppSettingsContent(",
            end = "internal fun GitHubProjectFooter(",
        )

        assertThat(app).contains("SettingsGroup(")
        assertThat(app).contains("HorizontalDivider(")
        assertThat(app).contains("settings_section_general")
        assertThat(app).contains("settings_section_model_presets")
        assertThat(app).contains("settings_section_knowledge")
        assertThat(app).contains("settings_section_tools")
        assertThat(app).contains("settings_section_data")
        assertThat(app).contains("settings_section_about")
        assertThat(app).contains("MaterialTheme.typography.titleSmall")
        val settingsGroup = app.substringAfter("private fun SettingsGroup(")
            .substringBefore("private fun SettingsGroupDivider(")
        assertThat(settingsGroup).doesNotContain("Surface(")
        assertThat(app).doesNotContain("SettingsSectionHeader")
        assertThat(app).doesNotContain("NexaraGlassCard")
        assertThat(app).doesNotContain("verticalArrangement = Arrangement.spacedBy(8.dp)")
    }

    @Test
    fun `profile and github footer use material roles without legacy glass or hardcoded type`() {
        val profile = sourceBlock(
            start = "private fun UserProfileHeader(",
            end = "private fun AddProviderButton(",
        )
        val footer = sourceBlock(
            start = "internal fun GitHubProjectFooter(",
            end = "private fun ProviderSettingsContent(",
        )

        assertThat(profile).contains("Surface(")
        assertThat(profile).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(profile).contains("MaterialTheme.typography")
        assertThat(profile).contains("NexaraSpacing.MinimumTouchTarget")
        assertThat(profile).doesNotContain("NexaraGlassCard")
        assertThat(profile).doesNotContain("Brush.linearGradient")
        assertThat(profile).doesNotContain("NexaraColors")

        assertThat(footer).contains("MaterialTheme.typography")
        assertThat(footer).contains("MaterialTheme.colorScheme")
        assertThat(footer).doesNotContain("fontSize = 11.sp")
        assertThat(footer).doesNotContain("fontSize = 12.sp")
        assertThat(footer).doesNotContain("NexaraTypography")
        assertThat(footer).doesNotContain("SpaceGrotesk")
    }

    @Test
    fun `provider screen has one material add action and a clear empty state`() {
        val providerContent = sourceBlock(
            start = "private fun ProviderSettingsContent(",
            end = "private fun UserProfileHeader(",
        )
        val addAction = sourceBlock(
            start = "private fun AddProviderButton(",
            end = "private fun ProviderCard(",
        )

        assertThat(providerContent).contains("AddProviderButton(")
        assertThat(providerContent).contains("settings_provider_empty")
        assertThat(providerContent).contains("MaterialTheme.typography")
        assertThat(providerContent).contains("NexaraSpacing.XLarge")
        assertThat(providerContent).doesNotContain("NexaraTypography")
        assertThat(providerContent).doesNotContain("NexaraColors")

        assertThat(addAction).contains("ListItem(")
        assertThat(addAction).contains("containerColor = Color.Transparent")
        assertThat(addAction).doesNotContain("Button(")
        assertThat(addAction).contains("UiTags.SETTINGS_ADD_PROVIDER")
        assertThat(addAction).contains("NexaraSpacing.MinimumTouchTarget")
        assertThat(addAction).contains("MaterialTheme.typography")
        assertThat(addAction).doesNotContain("NexaraGlassCard")
        assertThat(addAction).contains(".clickable(")
    }

    @Test
    fun `provider empty state adapts to compact landscape height`() {
        val providerContent = sourceBlock(
            start = "private fun ProviderSettingsContent(",
            end = "private fun UserProfileHeader(",
        )

        assertThat(providerContent).contains("LocalConfiguration.current.screenHeightDp")
        assertThat(providerContent).contains("compactHeightEmptyState")
        assertThat(providerContent).contains("ProviderEmptyState(")
        assertThat(providerContent).contains("if (compactHeight) {")
        assertThat(providerContent).contains("Row(")
    }

    @Test
    fun `provider row has one manage target and overflow owns edit and delete`() {
        val providerRow = sourceBlock(
            start = "private fun ProviderCard(",
            end = "private fun NameEditDialog(",
        )

        assertThat(providerRow).contains("ListItem(")
        assertThat(providerRow).contains("containerColor = Color.Transparent")
        assertThat(providerRow).doesNotContain("Surface(")
        assertThat(providerRow).doesNotContain("Icons.Rounded.ChevronRight")
        assertThat(providerRow).contains("Icons.Rounded.MoreVert")
        assertThat(providerRow).contains("DropdownMenu(")
        assertThat(providerRow).contains("DropdownMenuItem(")
        assertThat(providerRow).contains("UiTags.settingsProviderCard(provider.id)")
        assertThat(providerRow).contains("UiTags.settingsProviderActions(provider.id)")
        assertThat(providerRow).contains("}:edit")
        assertThat(providerRow).contains("}:delete")
        assertThat(providerRow).contains("provider.name")
        assertThat(providerRow).contains("provider.typeName")
        assertThat(providerRow).contains("provider.baseUrl")
        assertThat(providerRow).contains("settings_provider_status_summary")
        assertThat(providerRow).contains("settings_provider_state_enabled")
        assertThat(providerRow).contains("settings_provider_state_disabled")
        assertThat(providerRow).contains("settings_provider_api_key_configured")
        assertThat(providerRow).contains("settings_provider_api_key_not_configured")
        assertThat(providerRow).contains("settings_provider_vertex_credentials_configured")
        assertThat(providerRow).contains("settings_provider_vertex_credentials_not_configured")
        assertThat(providerRow).contains(
            "ProtocolType.Local -> stringResource(R.string.settings_provider_credentials_not_required)",
        )
        assertThat(providerRow).contains("stateDescription = providerStateDescription")
        assertThat(providerRow).contains("MaterialTheme.typography.labelMedium")
        assertThat(providerRow).contains("val titleStyle = if (useLargeTextLayout)")
        assertThat(providerRow).contains("maxLines = if (useLargeTextLayout) 3 else 2")
        assertThat(providerRow).contains("TextOverflow.Ellipsis")
        assertThat(providerRow).doesNotContain("provider.model")
        assertThat(providerRow).doesNotContain("connectionStatus")
        assertThat(providerRow).doesNotContain("modelCount")
        assertThat(providerRow).doesNotContain("NexaraGlassCard")
        assertThat(providerRow).doesNotContain("NexaraTypography")
        assertThat(providerRow).doesNotContain("NexaraColors")
        assertThat(providerRow).doesNotContain("fontSize =")
        assertThat(providerRow.split("IconButton(").size - 1).isEqualTo(1)
        assertThat(providerRow.split("onClick = onClick").size - 1).isEqualTo(1)
    }

    @Test
    fun `provider list is continuous and separates rows without card gaps`() {
        val providerContent = sourceBlock(
            start = "private fun ProviderSettingsContent(",
            end = "private fun UserProfileHeader(",
        )

        assertThat(providerContent).contains("verticalArrangement = Arrangement.spacedBy(0.dp)")
        assertThat(providerContent).contains("itemsIndexed(")
        assertThat(providerContent).contains("SettingsGroupDivider()")
    }

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
