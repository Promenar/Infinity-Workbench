package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class ProviderModelsScreenContractTest {
    @Test
    fun `顶部操作使用M3主次层级且批量动作进入overflow`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )
        val topActions = source.substringAfter("internal fun ProviderModelsTopActions(")
            .substringBefore("internal fun EnhancedModelCard(")

        assertThat(source).contains("internal fun ProviderModelsTopActions(")
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
        assertThat(source).contains("ModalBottomSheet(")
        assertThat(source).contains("rememberModalBottomSheetState(skipPartiallyExpanded = true)")
        val addForm = source.substringAfter("internal fun AddCustomModelForm(")
            .substringBefore("internal fun ProviderModelsTopActions(")
        assertThat(addForm).contains("LazyColumn(")
        assertThat(addForm).contains("OutlinedTextField(")
        assertThat(addForm).contains("imePadding()")
    }

    @Test
    fun `模型使用连续透明列表行并按折叠展开渐进披露`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )
        val modelCard = source.substringAfter("internal fun EnhancedModelCard(")
            .substringBefore("private fun formatTokens")

        assertThat(modelCard).contains("var expanded by remember(model.id) { mutableStateOf(initiallyExpanded) }")
        assertThat(modelCard).doesNotContain("Surface(")
        assertThat(modelCard).contains("modifier = modifier")
        assertThat(modelCard).contains("if (expanded) {")
        assertThat(modelCard).contains("OutlinedTextField(")
        assertThat(modelCard).contains("FlowRow(")
        assertThat(modelCard).contains("FilterChip(")
        assertThat(modelCard).contains("summaryCapabilities.take(2)")
        assertThat(modelCard).contains("remainingCapabilityCount")
        assertThat(modelCard).contains("contentDescription = remoteModelId")
        assertThat(modelCard).contains("maxLines = 1")
        assertThat(modelCard).contains("KeyboardType.Number")
        assertThat(modelCard).contains("model.remoteModelId.ifBlank")
        assertThat(modelCard).contains("providerModelsDeleteConfirmDialog(model.id)")
        assertThat(modelCard).contains("providerModelsDeleteConfirmButton(model.id)")
        assertThat(modelCard).doesNotContain("NexaraGlassCard(")
        assertThat(modelCard).doesNotContain("BasicTextField(")
        assertThat(modelCard).doesNotContain("CompactSelectableChip(")
        assertThat(modelCard).doesNotContain(".width(112.dp)")
    }

    @Test
    fun `模型列表使用零间距和内缩分隔线`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )
        val list = source.substringAfter("LazyColumn(")
            .substringBefore("if (showDeleteAllDialog)")

        assertThat(list).contains("verticalArrangement = Arrangement.spacedBy(0.dp)")
        assertThat(list).contains("itemsIndexed(")
        assertThat(list).contains("HorizontalDivider(")
    }

    @Test
    fun `content contract contains stable state actions and no vm or context usage`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )

        assertThat(source).contains("data class ProviderModelsScreenState")
        assertThat(source).contains("data class ProviderModelsScreenActions")
        assertThat(source).contains("fun ProviderModelsScreenContent")
        assertThat(source).contains("internal fun ProviderModelsScreenContent")
        val contentStart = source.indexOf("internal fun ProviderModelsScreenContent")
        val contentEnd = source.indexOf("private fun formatTokens")
        assertThat(contentStart).isAtLeast(0)
        assertThat(contentEnd).isGreaterThan(contentStart)
        val contentBody = source.substring(contentStart, contentEnd)
        assertThat(contentBody).doesNotContain("SettingsViewModel")
        assertThat(contentBody).doesNotContain("LocalContext")
        assertThat(source).contains("remember { mutableStateOf(\"\") }")
        assertThat(source).contains("it.name.contains(searchQuery, ignoreCase = true)")
        assertThat(source).contains("it.remoteModelId.contains(searchQuery, ignoreCase = true)")
        assertThat(source).contains("it.id.contains(searchQuery, ignoreCase = true)")
        assertThat(source).contains("NexaraSearchBar(")
    }

    @Test
    fun `route still wires real settings viewmodel with provider filters and callbacks`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )

        assertThat(source).contains("SettingsViewModel.factory(context.applicationContext as android.app.Application)")
        assertThat(source).contains("viewModel(factory")
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
        assertThat(source).contains(".withRecordedUserEdits(model)")
    }

    @Test
    fun `route content tags include stable provider model identifiers`() {
        val providerTags = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt"),
            ),
            Charsets.UTF_8,
        )
        assertThat(providerTags).contains("PROVIDER_MODELS_SCREEN_ROOT")
        assertThat(providerTags).contains("PROVIDER_MODELS_SEARCH_FIELD")
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_SYNC")
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_ADD")
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_OVERFLOW")
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_DISABLE_ALL")
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_DELETE_ALL")
        assertThat(providerTags).contains("PROVIDER_MODELS_NOTICE")
        assertThat(providerTags).contains("PROVIDER_MODELS_LIST")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_EMPTY")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_ERROR")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_LOADING")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_SEARCH_EMPTY")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_SHEET")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_FORM_LIST")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_ID_FIELD")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_NAME_FIELD")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_SUBMIT_BUTTON")
        assertThat(providerTags).contains("PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG")
        assertThat(providerTags).contains("PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON")
        assertThat(providerTags).contains("providerModelsModelCard")
        assertThat(providerTags).contains("providerModelsTestAction")
        assertThat(providerTags).contains("providerModelsDeleteAction")
        assertThat(providerTags).contains("providerModelsToggleAction")
        assertThat(providerTags).contains("providerModelsExpandAction")
        assertThat(providerTags).contains("providerModelsDetails")
        assertThat(providerTags).contains("providerModelsRemoteId")
        assertThat(providerTags).contains("providerModelsNameField")
        assertThat(providerTags).contains("providerModelsDeleteConfirmDialog")
        assertThat(providerTags).contains("providerModelsDeleteConfirmButton")
    }

    @Test
    fun `add sheet submit failure keeps sheet open and no close path in route callback`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )

        assertThat(source).contains("if (added) {")
        assertThat(source).contains("showAddDialog = false")
    }

    @Test
    fun `model draft synchronizes when the same model id is refreshed`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )

        assertThat(source).contains("var selectedType by remember(model.id)")
        assertThat(source).contains("var editName by remember(model.id)")
        assertThat(source).contains("var editContext by remember(model.id)")
        assertThat(source).contains("var activeCaps by remember(model.id)")
        assertThat(source).contains("LaunchedEffect(\n        model.id,\n        model.type,\n        model.name,")
        assertThat(source).contains("selectedType = model.type")
        assertThat(source).contains("editName = model.name")
        assertThat(source).contains("editContext = model.contextLength.toString()")
        assertThat(source).contains("activeCaps = model.capabilities.toSet()")
        assertThat(source).contains(
            "confirmButtonModifier = Modifier.testTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON)",
        )
    }

    @Test
    fun `同步反馈条使用MaterialTheme排版而非固定字号`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )
        val noticeBanner = source.substringAfter("internal fun ModelSyncNoticeBanner(")
            .substringBefore("private fun ProviderModelsListMessage(")

        assertThat(noticeBanner).contains("style = MaterialTheme.typography.bodySmall")
        assertThat(noticeBanner).doesNotContain("fontSize = 12.sp")
        assertThat(noticeBanner).doesNotContain("NexaraTypography")
    }
}
