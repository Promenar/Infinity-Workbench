package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class ProviderModelsScreenContractTest {
    @Test
    fun `模型管理密度契约应避免重复48dp尺寸与漂浮小标签`() {
        val source = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt"),
            ),
            Charsets.UTF_8,
        )
        val actionChip = source.substringAfter("internal fun ActionChip(")
            .substringBefore("internal fun EnhancedModelCard(")
        val modelCard = source.substringAfter("internal fun EnhancedModelCard(")
            .substringBefore("private fun formatTokens")

        assertThat(actionChip).contains(".height(48.dp)")
        assertThat(actionChip).doesNotContain(".padding(vertical = 6.dp)")
        assertThat(actionChip).doesNotContain(".heightIn(min = 48.dp)")
        assertThat(modelCard).contains("CompactSelectableChip(")
        assertThat(modelCard).contains("visualHeight = 36.dp")
        assertThat(modelCard).contains(".width(112.dp)")
        assertThat(modelCard).contains(".height(48.dp)")
        assertThat(modelCard).contains("contentAlignment = Alignment.CenterStart")
        assertThat(modelCard).contains("KeyboardType.Number")
        assertThat(modelCard).contains("model.remoteModelId.ifBlank")
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
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_DISABLE_ALL")
        assertThat(providerTags).contains("PROVIDER_MODELS_ACTION_DELETE_ALL")
        assertThat(providerTags).contains("PROVIDER_MODELS_NOTICE")
        assertThat(providerTags).contains("PROVIDER_MODELS_LIST")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_EMPTY")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_ERROR")
        assertThat(providerTags).contains("PROVIDER_MODELS_STATE_LOADING")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_SHEET")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_ID_FIELD")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_NAME_FIELD")
        assertThat(providerTags).contains("PROVIDER_MODELS_ADD_SUBMIT_BUTTON")
        assertThat(providerTags).contains("PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG")
        assertThat(providerTags).contains("PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON")
        assertThat(providerTags).contains("providerModelsModelCard")
        assertThat(providerTags).contains("providerModelsTestAction")
        assertThat(providerTags).contains("providerModelsDeleteAction")
        assertThat(providerTags).contains("providerModelsToggleAction")
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
}
