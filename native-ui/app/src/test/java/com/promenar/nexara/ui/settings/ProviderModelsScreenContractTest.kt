package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class ProviderModelsScreenContractTest {
    @Test
    fun `顶部操作使用M3主次层级且批量动作进入overflow`() {
        val source = readProviderModelsSource()
        val topActions = extractBetween(
            source,
            "internal fun ProviderModelsTopActions(",
            "@Composable\ninternal fun ProviderModelsModelRow(",
        )

        assertThat(topActions).contains("FilledTonalButton(")
        assertThat(topActions).contains("Button(")
        assertThat(topActions).contains("DropdownMenu(")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_OVERFLOW")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL")
        assertThat(topActions).contains("UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL")
        assertThat(source).doesNotContain("ProviderModelsActionsGrid(")
        assertThat(source).doesNotContain("internal fun ActionChip(")
        assertThat(source).doesNotContain("NexaraBottomSheet")
        assertThat(source).doesNotContain("fillMaxHeight(0.7f)")

        val addForm = extractBetween(
            source,
            "internal fun AddCustomModelForm(",
            "@Composable\ninternal fun ProviderModelsTopActions(",
        )
        assertThat(addForm).contains("LazyColumn(")
        assertThat(addForm).contains("OutlinedTextField(")
        assertThat(addForm).contains("imePadding()")
    }

    @Test
    fun `列表行使用连续文本能力摘要而非标签墙`() {
        val source = readProviderModelsSource()
        val row = extractBetween(
            source,
            "internal fun ProviderModelsModelRow(",
            "private fun capabilityLabelResource(",
        )

        assertThat(row).contains("localizedCapabilities.joinToString(\" · \")")
        assertThat(row).contains("text = capabilitySummary")
        assertThat(row).contains("model.remoteModelId.ifBlank")
        assertThat(row).contains("UiTags.providerModelsModelCard(model.id)")
        assertThat(row).contains("UiTags.providerModelsRemoteId(model.id)")
        assertThat(row).contains("UiTags.providerModelsToggleAction(model.id)")
        assertThat(row).contains("onRowClick")
        assertThat(row).contains("onToggle")
        assertThat(row).doesNotContain("OutlinedTextField(")
        assertThat(row).doesNotContain("FilterChip(")
        assertThat(row).doesNotContain("KeyboardType.Number")
        assertThat(row).doesNotContain("AnimatedVisibility(")
        assertThat(row).doesNotContain("NexaraGlassCard(")
    }

    @Test
    fun `列表行点击打开独立编辑面板且Switch使用独立回调`() {
        val source = readProviderModelsSource()
        val content = extractBetween(
            source,
            "internal fun ProviderModelsScreenContent(",
            "@OptIn(ExperimentalMaterial3Api::class)",
        )

        assertThat(content).contains("var selectedModelId by rememberSaveable")
        assertThat(content).contains("state.models.firstOrNull { it.id == selectedModelId }")
        assertThat(content).contains("ProviderModelsModelRow(")
        val rowClick = extractBetween(
            content,
            "onRowClick = {",
            "onToggle = { actions.onToggle(model.id) }",
        )
        assertThat(rowClick).contains("focusManager.clearFocus()")
        assertThat(rowClick).contains("keyboardController?.hide()")
        assertThat(rowClick).contains("selectedModelId = model.id")
        assertThat(content).contains("onToggle = { actions.onToggle(model.id) }")
        assertThat(content).contains("ModelEditorSheet(")
        assertThat(content).contains("onDismissRequest = { selectedModelId = null }")
    }

    @Test
    fun `独立编辑面板承载编辑测试删除和真实用户覆盖来源`() {
        val source = readEditorSource()
        assertThat(source).contains("internal fun ModelEditorSheet(")
        val editor = source.substring(source.indexOf("internal fun ModelEditorSheet("))

        assertThat(editor).contains("var selectedType by remember(model.id)")
        assertThat(editor).contains("var editName by remember(model.id)")
        assertThat(editor).contains("var editContext by remember(model.id)")
        assertThat(editor).contains("var activeCaps by remember(model.id)")
        assertThat(editor).contains("LaunchedEffect(")
        assertThat(editor).contains("withRecordedUserEdits(model)")
        assertThat(editor).contains("model.userEditedFields")
        assertThat(editor).doesNotContain("autoMetadataFingerprint")
        assertThat(editor).contains("onTest")
        assertThat(editor).contains("onCancel")
        assertThat(editor).contains("onDelete")
        assertThat(editor).contains("UiTags.providerModelsEditorSheet(model.id)")
        assertThat(editor).contains("UiTags.providerModelsEditorSource(model.id)")
        assertThat(editor).contains("UiTags.providerModelsEditorDeleteAction(model.id)")
    }

    @Test
    fun `模型列表保持常驻搜索稳定 key 与独立 Bettbox 分组卡`() {
        val source = readProviderModelsSource()
        val content = extractBetween(
            source,
            "internal fun ProviderModelsScreenContent(",
            "@OptIn(ExperimentalMaterial3Api::class)",
        )

        assertThat(content).contains("rememberSaveable(state.providerId)")
        assertThat(content).contains("NexaraSearchBar(")
        assertThat(content).contains("UiTags.PROVIDER_MODELS_SEARCH_FIELD")
        assertThat(content).contains("it.name.contains(searchQuery, ignoreCase = true)")
        assertThat(content).contains("it.remoteModelId.contains(searchQuery, ignoreCase = true)")
        assertThat(content).contains("it.id.contains(searchQuery, ignoreCase = true)")
        assertThat(content).contains("verticalArrangement = Arrangement.spacedBy(8.dp)")
        assertThat(content).contains("itemsIndexed(")
        assertThat(content).contains("key = { _, model -> model.id }")
        assertThat(content).contains("BettboxListGroup {")
    }

    @Test
    fun `content contract contains stable state actions and no vm or context usage`() {
        val source = readProviderModelsSource()
        val content = extractBetween(
            source,
            "internal fun ProviderModelsScreenContent(",
            "@OptIn(ExperimentalMaterial3Api::class)",
        )

        assertThat(source).contains("data class ProviderModelsScreenState")
        assertThat(source).contains("data class ProviderModelsScreenActions")
        assertThat(content).doesNotContain("SettingsViewModel")
        assertThat(content).doesNotContain("LocalContext")
    }

    @Test
    fun `route still wires real settings viewmodel with provider filters and callbacks`() {
        val source = readProviderModelsSource()

        assertThat(source).contains("SettingsViewModel.factory(context.applicationContext as android.app.Application)")
        assertThat(source).contains("providers.find { it.id == providerId }")
        assertThat(source).contains("models.filter { it.providerId == providerId }")
        assertThat(source).contains("onRefresh = { viewModel.refreshProviderModels(providerId) }")
        assertThat(source).contains("onUpdate = { viewModel.updateUserModel(it) }")
        assertThat(source).contains("onAdd = { id, name -> viewModel.addCustomModel(providerId, id, name) }")
        assertThat(source).contains("onDisableAll = { viewModel.disableAllModels(providerId) }")
        assertThat(source).contains("onDeleteAll = { viewModel.deleteAllModels(providerId) }")
        assertThat(source).contains("onToggle = { modelId -> viewModel.toggleModel(modelId) }")
        assertThat(source).contains("onTest = { modelId -> viewModel.testModel(modelId) }")
        assertThat(source).contains("onCancelTest = { modelId -> viewModel.cancelModelTest(modelId) }")
        assertThat(source).contains("onDelete = { modelId -> viewModel.deleteModel(modelId) }")
        assertThat(source).contains("onClearNotice = { viewModel.clearSyncNotice() }")
    }

    @Test
    fun `route content tags include stable provider model identifiers`() {
        val tags = readUiTags()
        listOf(
            "PROVIDER_MODELS_SCREEN_ROOT",
            "PROVIDER_MODELS_SEARCH_FIELD",
            "PROVIDER_MODELS_ACTION_SYNC",
            "PROVIDER_MODELS_ACTION_ADD",
            "PROVIDER_MODELS_ACTION_OVERFLOW",
            "PROVIDER_MODELS_ACTION_DISABLE_ALL",
            "PROVIDER_MODELS_ACTION_DELETE_ALL",
            "PROVIDER_MODELS_NOTICE",
            "PROVIDER_MODELS_LIST",
            "PROVIDER_MODELS_ADD_SHEET",
            "PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG",
            "providerModelsModelCard",
            "providerModelsToggleAction",
            "providerModelsRemoteId",
            "providerModelsEditorSheet",
            "providerModelsEditorList",
            "providerModelsEditorCloseAction",
            "providerModelsEditorTestAction",
            "providerModelsEditorSource",
            "providerModelsEditorDeleteAction",
            "providerModelsEditorDeleteConfirmDialog",
            "providerModelsEditorDeleteConfirmButton",
        ).forEach { assertThat(tags).contains(it) }
    }

    @Test
    fun `add sheet submit failure keeps sheet open and no close path in route callback`() {
        val source = readProviderModelsSource()
        assertThat(source).contains("if (added) {")
        assertThat(source).contains("showAddDialog = false")
    }

    @Test
    fun `同步反馈条使用MaterialTheme排版而非固定字号`() {
        val source = readProviderModelsSource()
        val notice = extractBetween(
            source,
            "internal fun ModelSyncNoticeBanner(",
            "private fun ProviderModelsListMessage(",
        )

        assertThat(notice).contains("style = MaterialTheme.typography.bodySmall")
        assertThat(notice).doesNotContain("fontSize = 12.sp")
        assertThat(notice).doesNotContain("NexaraTypography")
    }

    private fun extractBetween(source: String, startMarker: String, endMarker: String): String {
        assertThat(source).contains(startMarker)
        assertThat(source).contains(endMarker)
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertThat(end).isGreaterThan(start)
        return source.substring(start, end)
    }

    private fun readProviderModelsSource(): String = read(
        "app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt",
    )

    private fun readEditorSource(): String = read(
        "app/src/main/java/com/promenar/nexara/ui/settings/ModelEditorSheet.kt",
    )

    private fun readUiTags(): String = read(
        "app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt",
    )

    private fun read(path: String): String = String(
        Files.readAllBytes(Path.of(path)),
        Charsets.UTF_8,
    )
}
