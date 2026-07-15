package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import com.promenar.nexara.data.local.inference.SlotState
import com.promenar.nexara.data.remote.protocol.ProtocolType

class SecureSecretUiContractTest {
    private fun source(relative: String): String = String(
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/$relative")),
        Charsets.UTF_8,
    )

    @Test
    fun `provider form uses typed credential mutation and never loads full config into compose state`() {
        val form = source("ui/settings/ProviderFormScreen.kt")
        val nav = source("navigation/NavGraph.kt")

        assertThat(form).contains("CredentialUpdate.Preserve")
        assertThat(form).contains("CredentialUpdate.Replace")
        assertThat(form).contains("CredentialUpdate.Clear")
        assertThat(form).contains("SecretField(")
        assertThat(form).doesNotContain("apiKey = config.apiKey")
        assertThat(form).contains("getProviderSummary(providerId)")
        assertThat(nav).doesNotContain("apiKey.toCredentialUpdate()")
        assertThat(nav).contains("withContext(kotlinx.coroutines.Dispatchers.IO)")
        assertThat(form).contains("credentialKindMismatch")
        assertThat(form).contains("isSaving")
        assertThat(form).contains("catch (_: Exception)")
        assertThat(source("ui/settings/SettingsViewModel.kt")).contains("suspend fun getProviderSummary")
    }

    @Test
    fun `provider form exposes a pure Material 3 content seam without glass or fixed IME spacers`() {
        val form = source("ui/settings/ProviderFormScreen.kt")

        assertThat(form).contains("data class ProviderFormUiState")
        assertThat(form).contains("data class ProviderFormActions")
        assertThat(form).contains("fun ProviderFormContent(")
        assertThat(form).contains("imePadding = true")
        assertThat(form).contains("scrollable = false")
        assertThat(form).contains("LazyColumn(")
        assertThat(form).contains("ExposedDropdownMenuBox(")
        assertThat(form).contains("OutlinedTextField(")
        assertThat(form).contains("OutlinedButton(")
        assertThat(form).contains("Button(")
        assertThat(form).contains("liveRegion = LiveRegionMode.Assertive")
        assertThat(form).doesNotContain("NexaraGlassCard")
        assertThat(form).doesNotContain("GlassInputField")
        assertThat(form).doesNotContain("GlassBorder")
        assertThat(form).doesNotContain("PresetItem")
        assertThat(form).doesNotContain("import com.promenar.nexara.ui.common.ProtocolSelector")
        assertThat(form).doesNotContain("BasicTextField")
        assertThat(form).doesNotContain("Spacer(modifier = Modifier.height(200.dp))")
        assertThat(form).doesNotContain("navigationBarsPadding()")
        assertThat(form).doesNotContain("imePadding()")
    }

    @Test
    fun `provider connection and save are mutually exclusive and cancellation is never swallowed`() {
        val form = source("ui/settings/ProviderFormScreen.kt")

        assertThat(form).contains("connectionTestState != ProviderConnectionTestState.Testing")
        assertThat(form).contains("!state.isSaving")
        assertThat(form).contains("catch (cancelled: CancellationException)")
        assertThat(form).contains("throw cancelled")
        assertThat(form.substringBefore("fun ProviderFormContent(")).doesNotContain("runCatching {")
    }

    @Test
    fun `provider route owns effects while pure content owns only render state and callbacks`() {
        val form = source("ui/settings/ProviderFormScreen.kt")
        val content = form.substringAfter("fun ProviderFormContent(")

        assertThat(form.substringBefore("fun ProviderFormContent(")).contains("NexaraApplication")
        assertThat(form.substringBefore("fun ProviderFormContent(")).contains("SettingsViewModel")
        assertThat(content).doesNotContain("NexaraApplication")
        assertThat(content).doesNotContain("SettingsViewModel")
        assertThat(content).doesNotContain("LocalContext")
        assertThat(content).doesNotContain("rememberCoroutineScope")
        assertThat(content).doesNotContain("LaunchedEffect")
        assertThat(content).doesNotContain("ProtocolFactory")
    }

    @Test
    fun `provider cloud endpoint accepts only absolute HTTPS`() {
        assertThat(isSecureProviderEndpoint("https://api.example.com/v1")).isTrue()
        assertThat(isSecureProviderEndpoint("HTTP://api.example.com/v1")).isFalse()
        assertThat(isSecureProviderEndpoint("https:///missing-host")).isFalse()
        assertThat(isSecureProviderEndpoint("not-a-url")).isFalse()
    }

    @Test
    fun `credential mutation preserves stored values but keeps explicit clear unambiguous`() {
        assertThat(credentialUpdateForSecretInput("", hasStoredCredential = true))
            .isEqualTo(com.promenar.nexara.data.model.CredentialUpdate.Preserve)
        assertThat(credentialUpdateForSecretInput("", hasStoredCredential = false))
            .isEqualTo(com.promenar.nexara.data.model.CredentialUpdate.Clear)
        assertThat(credentialUpdateForSecretInput("fake-new", hasStoredCredential = true))
            .isEqualTo(com.promenar.nexara.data.model.CredentialUpdate.Replace("fake-new"))
    }

    @Test
    fun `search and skills share SecretField and persistent state exposes only secret presence`() {
        val screen = source("ui/settings/SearchConfigScreen.kt")
        val skills = source("ui/settings/SkillsScreen.kt")
        val stateFields = SearchConfigState::class.java.declaredFields.map { it.name }

        assertThat(stateFields).doesNotContain("tavilyApiKey")
        assertThat(stateFields).contains("hasTavilyApiKey")
        assertThat(screen).contains("SecretField(")
        assertThat(skills).contains("TavilySecretEditor(searchViewModel, searchState)")
        assertThat(skills).doesNotContain("Paste API Key here")
    }

    @Test
    fun `secret reveal is transient clearable and never saveable`() {
        val field = source("ui/common/SecretField.kt")

        assertThat(field).contains("CharArray?")
        assertThat(field).contains("DisposableEffect")
        assertThat(field).contains("fill('\\u0000')")
        assertThat(field).doesNotContain("rememberSaveable(")
        assertThat(field).contains("Modifier.size(48.dp)")
        assertThat(field).contains("revealGeneration")
        assertThat(field).contains("revealJob?.cancel()")
        assertThat(field).contains("DisposableEffect(hasStoredSecret, lifecycleOwner)")
        assertThat(field).doesNotContain("remember(hasStoredSecret)")
        assertThat(field).doesNotContain("everFocused")
        assertThat(field).contains("focusGeneration")
        assertThat(field).contains("OutlinedTextField(")
        assertThat(field).contains("SECRET_REVEAL_TIMEOUT_MILLIS")
        assertThat(field).contains("revealTimeoutJob")
        assertThat(field).contains("delay(revealTimeoutMillis)")
        assertThat(field).contains("Lifecycle.Event.ON_STOP")
        assertThat(field).contains("KeyboardType.Password")
        assertThat(field).contains("autoCorrectEnabled = false")
        assertThat(field).contains("readOnly = hasStoredSecret && value.isEmpty()")
        assertThat(field).doesNotContain(".focusable()")
        assertThat(field).doesNotContain("BasicTextField")
        assertThat(field).doesNotContain("GlassBorder")
        assertThat(field).doesNotContain("SolidColor")
    }

    @Test
    fun `local effective protocol bypasses cloud HTTPS while custom cloud stays protected`() {
        assertThat(isProviderEndpointAllowed(ProtocolType.Local, "")).isTrue()
        assertThat(isProviderEndpointAllowed(ProtocolType.Generic_OpenAI_Compat, "http://plain.invalid")).isFalse()
        assertThat(isProviderEndpointAllowed(ProtocolType.Generic_OpenAI_Compat, "https://secure.invalid")).isTrue()
    }

    @Test
    fun `local connection requires an actually loaded named main model`() {
        assertThat(localProviderModelsForConnection(SlotState())).isEmpty()
        assertThat(
            localProviderModelsForConnection(SlotState(isLoaded = true, modelName = ""))
        ).isEmpty()
        assertThat(
            localProviderModelsForConnection(SlotState(isLoaded = true, modelName = "local.gguf"))
        ).containsExactly("local.gguf")
        assertThat(source("ui/settings/LocalModelsViewModel.kt"))
            .contains("app.localInferenceEngine")
        assertThat(source("ui/settings/LocalModelsViewModel.kt"))
            .doesNotContain("LocalInferenceEngine(appContext)")
    }

    @Test
    fun `backup screen provides password gates and real remote selection flow`() {
        val screen = source("ui/settings/BackupSettingsScreen.kt")

        assertThat(screen).contains("viewModel.listRemote()")
        assertThat(screen).contains("viewModel.selectRemote(remote)")
        assertThat(screen).contains("viewModel.restoreSelectedRemote(")
        assertThat(screen).contains("viewModel.upload(")
        assertThat(screen).contains("passwordConfirmation")
        assertThat(screen).contains("showExportPasswordDialog")
        assertThat(screen).contains("showUploadPasswordDialog")
        assertThat(screen).contains("showRestorePasswordDialog")
        assertThat(screen).contains("uiState.canExecute")
        assertThat(screen).contains("uiState.operation.isCancellable")
        assertThat(screen).contains("imePadding()")
        assertThat(screen).contains("viewModel.reportDocumentError(")
        assertThat(source("ui/settings/BackupViewModel.kt")).doesNotContain("val statusMessage: String?")
    }

    @Test
    fun `search editors persist only on explicit save`() {
        val screen = source("ui/settings/SearchConfigScreen.kt")
        val skills = source("ui/settings/SkillsScreen.kt")

        assertThat(screen).contains("saveTavilyApiKey(")
        assertThat(skills).contains("TavilySecretEditor(searchViewModel, searchState)")
        assertThat(screen).doesNotContain("if (it.isNotBlank()) viewModel.updateTavilyApiKey(it)")
        assertThat(skills).doesNotContain("if (it.isNotBlank()) searchViewModel.updateTavilyApiKey(it)")
        assertThat(source("ui/settings/SearchConfigViewModel.kt")).contains("SearchSecretOperation.Saving")
    }
}
