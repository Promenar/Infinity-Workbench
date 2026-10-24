package com.promenar.nexara.ui.settings

import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.local.inference.SlotState
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.ProviderSummary
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbe
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.nexara.data.remote.protocol.ProviderEndpointOperation
import com.promenar.nexara.data.remote.protocol.ProviderEndpointResolver
import com.promenar.nexara.data.remote.protocol.ProviderEndpointTarget
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProviderPreset(
    val name: String,
    val protocolType: ProtocolType,
    val defaultBaseUrl: String,
    val iconRes: Int? = null,
)

internal enum class ProviderConnectionTestState { Idle, Testing, Success, Unavailable, Error }

internal data class ProviderFormUiState(
    val isEditing: Boolean = false,
    val isExistingProvider: Boolean = false,
    val name: String = "",
    val selectedPreset: ProviderPreset = PROVIDER_PRESETS.first(),
    val availablePresets: List<ProviderPreset> = PROVIDER_PRESETS,
    val presetMenuExpanded: Boolean = false,
    val baseUrl: String = PROVIDER_PRESETS.first().defaultBaseUrl,
    val secretInput: String = "",
    val hasStoredCredential: Boolean = false,
    val localProtocol: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
    val protocolMenuExpanded: Boolean = false,
    val usesVertexCredential: Boolean = false,
    val isLocal: Boolean = false,
    val endpointValid: Boolean = true,
    val protocolUnsupported: Boolean = false,
    val legacyMigrationBlocked: Boolean = false,
    val credentialKindMismatch: Boolean = false,
    val unavailableLocalConfiguration: Boolean = false,
    val connectionTestState: ProviderConnectionTestState = ProviderConnectionTestState.Idle,
    val connectionTestFailureMessage: String? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val localConnectionFailed: Boolean = false,
    val onboardingMode: Boolean = false,
    /** 云端提供商的 API 源（公网或 Tailscale 内网）；本地推理为 null。 */
    val apiSource: ProviderApiSourceState? = null,
    val tailnetInfo: ProviderTailnetInfo = ProviderTailnetInfo(),
)

internal data class ProviderFormActions(
    val onBack: () -> Unit = {},
    val onPresetMenuExpandedChange: (Boolean) -> Unit = {},
    val onPresetSelected: (ProviderPreset) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onBaseUrlChange: (String) -> Unit = {},
    val onSecretChange: (String) -> Unit = {},
    val onRevealSecret: suspend () -> CharArray? = { null },
    val onClearSecret: () -> Unit = {},
    val onProtocolSelected: (ProtocolType) -> Unit = {},
    val onProtocolMenuExpandedChange: (Boolean) -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onLocalPrimaryAction: () -> Unit = {},
    val onOpenLocalModels: () -> Unit = {},
    val onApiSourceSelected: (ProviderApiSource) -> Unit = {},
    val onTailnetPeerSelected: (com.promenar.nexara.data.tailnet.TailnetPeer) -> Unit = {},
    val onOpenTailscaleSettings: () -> Unit = {},
)

internal suspend fun persistVerifiedProviderConnection(
    protocolType: ProtocolType,
    baseUrl: String,
    credential: CredentialUpdate,
    model: String,
    name: String?,
    onSave: suspend (ProtocolType, String, CredentialUpdate, String, String?) -> String,
    readSummary: suspend (String) -> ProviderSummary?,
): String {
    val savedProviderId = onSave(protocolType, baseUrl, credential, model, name)
    val summary = checkNotNull(readSummary(savedProviderId)) { "保存后的提供商不可读" }
    check(summary.protocolType == protocolType && summary.baseUrl == baseUrl) {
        "保存后的提供商配置与已验证输入不一致"
    }
    when (credential) {
        is CredentialUpdate.Replace -> check(
            if (protocolType is ProtocolType.Google_VertexAI) summary.hasVertexCredentials else summary.hasApiKey,
        ) { "保存后的提供商凭证不可用" }
        CredentialUpdate.Clear -> check(!summary.hasApiKey && !summary.hasVertexCredentials) {
            "提供商凭证清除未生效"
        }
        CredentialUpdate.Preserve -> Unit
    }
    return savedProviderId
}

internal fun requiresLegacyCredentialMigration(
    legacyProtocol: Boolean,
    hasStoredCredential: Boolean,
    credentialUpdate: CredentialUpdate,
): Boolean = legacyProtocol && hasStoredCredential && credentialUpdate is CredentialUpdate.Preserve

val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", R.drawable.ic_provider_openai),
    ProviderPreset("DeepSeek", ProtocolType.DeepSeek, "https://api.deepseek.com", R.drawable.ic_provider_deepseek),
    ProviderPreset("Anthropic", ProtocolType.Anthropic_Messages, "https://api.anthropic.com", R.drawable.ic_provider_anthropic),
    ProviderPreset("Gemini", ProtocolType.Google_Gemini, "https://generativelanguage.googleapis.com", R.drawable.ic_provider_gemini),
    ProviderPreset("Vertex AI", ProtocolType.Google_VertexAI, "https://us-central1-aiplatform.googleapis.com", R.drawable.ic_provider_gemini),
    ProviderPreset("Kimi", ProtocolType.Moonshot_Kimi, "https://api.moonshot.cn", R.drawable.ic_provider_kimi),
    ProviderPreset("通义千问", ProtocolType.Qwen_DashScope, "https://dashscope.aliyuncs.com/compatible-mode", R.drawable.ic_provider_qwen),
    ProviderPreset("智谱", ProtocolType.Zhipu_GLM, "https://open.bigmodel.cn/api/paas", R.drawable.ic_provider_zhipu),
    ProviderPreset("豆包", ProtocolType.Doubao_ByteDance, "https://ark.cn-beijing.volces.com/api", R.drawable.ic_provider_doubao),
    ProviderPreset("百川", ProtocolType.Baichuan, "https://api.baichuan-ai.com", R.drawable.ic_provider_baichuan),
    ProviderPreset("Mistral", ProtocolType.Mistral_Chat, "https://api.mistral.ai", R.drawable.ic_provider_mistral),
    ProviderPreset("Local", ProtocolType.Local, "", R.drawable.ic_provider_local),
    ProviderPreset("Custom", ProtocolType.Generic_OpenAI_Compat, "", R.drawable.ic_provider_custom),
)

private val CUSTOM_PROTOCOL_OPTIONS = listOf(
    ProtocolType.Generic_OpenAI_Compat,
    ProtocolType.Anthropic_Messages,
    ProtocolType.OpenAI_Responses,
    ProtocolType.Google_Gemini,
)

internal const val PROVIDER_FORM_LIST_TAG = "provider_form_list"

@Composable
fun ProviderFormScreen(
    providerId: String? = null,
    forceLocalProbeFailureForTesting: Boolean = false,
    onNavigateBack: () -> Unit,
    onOpenTailscaleSettings: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onboardingMode: Boolean = false,
    onSaved: (String) -> Unit = {},
    onConnectionVerified: (String) -> Unit = {},
    onSave: suspend (ProtocolType, String, CredentialUpdate, String, String?) -> String = { _, _, _, _, _ -> "" },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val app = context.applicationContext as NexaraApplication
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val availableProviderPresets = remember(app.localInferenceRuntimeGate.isAvailable) {
        if (app.localInferenceRuntimeGate.isAvailable) {
            PROVIDER_PRESETS
        } else {
            PROVIDER_PRESETS.filterNot { it.protocolType is ProtocolType.Local }
        }
    }
    var name by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(availableProviderPresets.first()) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(PROVIDER_PRESETS.first().defaultBaseUrl) }
    var secretInput by remember { mutableStateOf("") }
    var credentialUpdate by remember { mutableStateOf<CredentialUpdate>(CredentialUpdate.Preserve) }
    var hasCredential by remember { mutableStateOf(false) }
    var originalUsesVertex by remember { mutableStateOf<Boolean?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var localConnectionFailed by remember { mutableStateOf(false) }
    var unavailableLocalConfiguration by remember { mutableStateOf(false) }
    var localProtocol by remember { mutableStateOf<ProtocolType>(ProtocolType.Generic_OpenAI_Compat) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }
    var connectionTestState by remember { mutableStateOf(ProviderConnectionTestState.Idle) }
    var connectionTestFailureMessage by remember { mutableStateOf<String?>(null) }
    var legacyUnsupportedProtocol by remember { mutableStateOf<ProtocolType?>(null) }
    var recoveringUnknownProtocol by remember { mutableStateOf(false) }
    // API 源不保存：未手动切换时跟随地址自动识别。
    var manualApiSource by remember { mutableStateOf<ProviderApiSource?>(null) }
    val tailnetState by app.tailnetManager.state.collectAsStateWithLifecycle()
    val connectionProbe = ProviderConnectionProbe.processScoped
    val scope = rememberCoroutineScope()

    LaunchedEffect(providerId) {
        if (providerId != null) {
            val config = if (forceLocalProbeFailureForTesting) {
                ProviderSummary(
                    id = providerId,
                    name = "Local",
                    protocolType = ProtocolType.Local,
                    baseUrl = "",
                    model = "",
                    hasApiKey = false,
                    hasVertexCredentials = false,
                )
            } else {
                viewModel.getProviderSummary(providerId)
            }
            if (config != null) {
                unavailableLocalConfiguration =
                    config.protocolType is ProtocolType.Local && !app.localInferenceRuntimeGate.isAvailable
                name = config.name
                baseUrl = config.baseUrl
                hasCredential = config.hasApiKey || config.hasVertexCredentials
                originalUsesVertex = config.hasVertexCredentials
                legacyUnsupportedProtocol = config.protocolType.takeIf(::isRetiredProviderProtocol)
                val matched = availableProviderPresets.find {
                    it.protocolType == config.protocolType &&
                        (it.name != "Custom" || config.protocolType == ProtocolType.Generic_OpenAI_Compat)
                }
                selectedPreset = when {
                    matched != null -> matched
                    legacyUnsupportedProtocol != null -> ProviderPreset(
                        name = config.protocolType.displayName,
                        protocolType = config.protocolType,
                        defaultBaseUrl = config.baseUrl,
                        iconRes = config.protocolType.iconRes,
                    )
                    else -> availableProviderPresets.last()
                }
                if (selectedPreset.name == "Custom") {
                    localProtocol = config.protocolType.takeUnless {
                        it is ProtocolType.Local && !app.localInferenceRuntimeGate.isAvailable
                    } ?: ProtocolType.Generic_OpenAI_Compat
                }
            } else {
                viewModel.getUnsupportedProviderSummary(providerId)?.let { unsupported ->
                    recoveringUnknownProtocol = true
                    name = unsupported.name
                    baseUrl = unsupported.baseUrl
                    hasCredential = unsupported.hasApiKey || unsupported.hasVertexCredentials
                    originalUsesVertex = when {
                        unsupported.hasVertexCredentials && !unsupported.hasApiKey -> true
                        unsupported.hasApiKey && !unsupported.hasVertexCredentials -> false
                        else -> null
                    }
                    selectedPreset = availableProviderPresets.last { it.name == "Custom" }
                    localProtocol = ProtocolType.Generic_OpenAI_Compat
                }
            }
        }
    }

    val effectiveProtocol = if (selectedPreset.name == "Custom") localProtocol else selectedPreset.protocolType
    val endpointValid = isProviderEndpointAllowed(effectiveProtocol, baseUrl)
    val protocolUnsupported = isRetiredProviderProtocol(effectiveProtocol)
    val legacyMigrationBlocked = requiresLegacyCredentialMigration(
        legacyProtocol = legacyUnsupportedProtocol != null || recoveringUnknownProtocol,
        hasStoredCredential = hasCredential,
        credentialUpdate = credentialUpdate,
    )
    val selectedUsesVertex = effectiveProtocol is ProtocolType.Google_VertexAI
    val apiSource = if (effectiveProtocol is ProtocolType.Local) null
        else providerApiSourceState(baseUrl, manualApiSource, tailnetState)
    val showsTailnet = apiSource?.selected == ProviderApiSource.TAILNET
    LaunchedEffect(showsTailnet, tailnetState.phase) {
        // 设备列表在设置页之外不会主动刷新；切到 Tailscale 内网时取一次最新节点。
        if (showsTailnet && tailnetState.phase == com.promenar.nexara.data.tailnet.TailnetPhase.RUNNING) {
            // 刷新失败只是沿用旧列表；离开页面时的取消必须继续向上传递。
            try {
                app.tailnetManager.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
    val credentialKindMismatch = hasCredential && credentialUpdate is CredentialUpdate.Preserve &&
        originalUsesVertex != null && originalUsesVertex != selectedUsesVertex

    val launchSave: () -> Unit = {
        if (!isSaving && connectionTestState != ProviderConnectionTestState.Testing &&
            endpointValid && !protocolUnsupported && !legacyMigrationBlocked && !credentialKindMismatch
        ) {
            isSaving = true
            saveFailed = false
            scope.launch {
                try {
                    val savedProviderId = onSave(
                        effectiveProtocol,
                        baseUrl,
                        credentialUpdate,
                        "",
                        name.ifBlank { null },
                    )
                    onSaved(savedProviderId)
                    if (onboardingMode) {
                        onNavigateBack()
                    } else if (providerId != null) {
                        onNavigateToModels()
                    } else {
                        onNavigateBack()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveFailed = true
                } finally {
                    isSaving = false
                }
            }
        }
    }

    val launchConnectionTest: () -> Unit = {
        if (connectionTestState != ProviderConnectionTestState.Testing && !isSaving &&
            endpointValid && !protocolUnsupported && !legacyMigrationBlocked && !credentialKindMismatch
        ) {
            connectionTestState = ProviderConnectionTestState.Testing
            connectionTestFailureMessage = null
            scope.launch {
                val probeResult = withContext(Dispatchers.IO) {
                    try {
                        if (effectiveProtocol is ProtocolType.Local) {
                            ProviderConnectionProbeResult.Success
                        } else {
                            val transient = when (val update = credentialUpdate) {
                                is CredentialUpdate.Replace -> update.value.toCharArray()
                                CredentialUpdate.Clear -> CharArray(0)
                                CredentialUpdate.Preserve -> providerId?.let {
                                    viewModel.revealProviderCredential(
                                        it,
                                        effectiveProtocol is ProtocolType.Google_VertexAI,
                                    )
                                } ?: CharArray(0)
                            }
                            try {
                                val credential = transient.concatToString()
                                connectionProbe.probe(UnifiedProviderConfig(
                                    protocolType = effectiveProtocol,
                                    baseUrl = baseUrl,
                                    apiKey = if (selectedUsesVertex) "" else credential,
                                    defaultModel = "",
                                    serviceAccountJson = if (selectedUsesVertex) credential else "",
                                    projectId = "",
                                    location = VERTEX_DEFAULT_LOCATION,
                                ))
                            } finally {
                                transient.fill('\u0000')
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ProviderConnectionProbeResult.Failure(
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE,
                        )
                    }
                }
                connectionTestState = when (probeResult) {
                    ProviderConnectionProbeResult.Success -> {
                        connectionTestFailureMessage = null
                        ProviderConnectionTestState.Success
                    }
                    ProviderConnectionProbeResult.Unsupported -> {
                        connectionTestFailureMessage = null
                        ProviderConnectionTestState.Unavailable
                    }
                    is ProviderConnectionProbeResult.Failure -> {
                        val reasonText = when (probeResult.reason) {
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED ->
                                resources.getString(R.string.provider_form_test_failed_auth)
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE ->
                                resources.getString(R.string.provider_form_test_failed_network)
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.ENDPOINT_INVALID ->
                                resources.getString(R.string.provider_form_test_failed_endpoint)
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.CREDENTIALS_INVALID ->
                                resources.getString(R.string.provider_form_test_failed_credentials)
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.CREDENTIALS_MISSING ->
                                resources.getString(R.string.provider_form_test_failed_missing)
                            com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure.RESPONSE_INVALID ->
                                resources.getString(R.string.provider_form_test_failed_response)
                        }
                        val prefix = resources.getString(R.string.common_cd_failed)
                        connectionTestFailureMessage = if (probeResult.statusCode != null) {
                            "$prefix (HTTP ${probeResult.statusCode})：$reasonText"
                        } else {
                            "$prefix：$reasonText"
                        }
                        ProviderConnectionTestState.Error
                    }
                }
                if (probeResult == ProviderConnectionProbeResult.Success && providerId != null) {
                    isSaving = true
                    saveFailed = false
                    try {
                        val savedProviderId = persistVerifiedProviderConnection(
                            protocolType = effectiveProtocol,
                            baseUrl = baseUrl,
                            credential = credentialUpdate,
                            model = "",
                            name = name.ifBlank { null },
                            onSave = onSave,
                            readSummary = viewModel::getProviderSummary,
                        )
                        onConnectionVerified(savedProviderId)
                        if (onboardingMode) onNavigateBack()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        connectionTestState = ProviderConnectionTestState.Error
                        saveFailed = true
                    } finally {
                        isSaving = false
                    }
                }
            }
        }
    }

    val launchLocalPrimary: () -> Unit = {
        if (!isSaving) {
            scope.launch {
                if (onboardingMode && providerId != null) {
                    isSaving = true
                    localConnectionFailed = false
                    val probeState = if (forceLocalProbeFailureForTesting) {
                        SlotState()
                    } else {
                        app.localInferenceEngine.mainSlot.value
                    }
                    val discoveredModels = localProviderModelsForConnection(probeState)
                    if (discoveredModels.isEmpty()) {
                        localConnectionFailed = true
                        isSaving = false
                        return@launch
                    }
                    try {
                        val savedProviderId = onSave(
                            ProtocolType.Local,
                            "",
                            CredentialUpdate.Preserve,
                            discoveredModels.first(),
                            "本地模型",
                        )
                        onConnectionVerified(savedProviderId)
                        onNavigateBack()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        localConnectionFailed = true
                    } finally {
                        isSaving = false
                    }
                    return@launch
                }
                isSaving = true
                saveFailed = false
                try {
                    val savedProviderId = onSave(
                        ProtocolType.Local,
                        "",
                        CredentialUpdate.Preserve,
                        "",
                        "本地模型",
                    )
                    onSaved(savedProviderId)
                    if (onboardingMode) onNavigateBack() else onNavigateToLocalModels()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveFailed = true
                } finally {
                    isSaving = false
                }
            }
        }
    }

    ProviderFormContent(
        state = ProviderFormUiState(
            isEditing = providerId != null,
            isExistingProvider = providerId != null,
            name = name,
            selectedPreset = selectedPreset,
            availablePresets = availableProviderPresets,
            presetMenuExpanded = presetMenuExpanded,
            baseUrl = baseUrl,
            secretInput = secretInput,
            hasStoredCredential = hasCredential,
            localProtocol = localProtocol,
            protocolMenuExpanded = protocolMenuExpanded,
            usesVertexCredential = effectiveProtocol is ProtocolType.Google_VertexAI,
            isLocal = effectiveProtocol is ProtocolType.Local,
            endpointValid = endpointValid,
            protocolUnsupported = protocolUnsupported,
            legacyMigrationBlocked = legacyMigrationBlocked,
            credentialKindMismatch = credentialKindMismatch,
            unavailableLocalConfiguration = unavailableLocalConfiguration,
            connectionTestState = connectionTestState,
            connectionTestFailureMessage = connectionTestFailureMessage,
            isSaving = isSaving,
            saveFailed = saveFailed,
            localConnectionFailed = localConnectionFailed,
            onboardingMode = onboardingMode,
            apiSource = apiSource,
            tailnetInfo = ProviderTailnetInfo(
                selfName = tailnetState.summary?.selfName,
                tailnetName = tailnetState.summary?.tailnetName,
                peers = tailnetState.peers,
            ),
        ),
        actions = ProviderFormActions(
            onBack = onNavigateBack,
            onPresetMenuExpandedChange = { presetMenuExpanded = it },
            onPresetSelected = { preset ->
                selectedPreset = preset
                if (preset.name != "Custom" && preset.name != "Local") {
                    name = preset.name
                    baseUrl = preset.defaultBaseUrl
                }
                manualApiSource = null
                presetMenuExpanded = false
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onNameChange = { name = it },
            onBaseUrlChange = {
                baseUrl = it
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onSecretChange = {
                secretInput = it
                credentialUpdate = credentialUpdateForSecretInput(it, hasCredential)
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onRevealSecret = {
                providerId?.let {
                    viewModel.revealProviderCredential(
                        it,
                        effectiveProtocol is ProtocolType.Google_VertexAI,
                    )
                }
            },
            onClearSecret = {
                secretInput = ""
                hasCredential = false
                credentialUpdate = CredentialUpdate.Clear
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onProtocolSelected = {
                localProtocol = it
                protocolMenuExpanded = false
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onProtocolMenuExpandedChange = { protocolMenuExpanded = it },
            onTestConnection = launchConnectionTest,
            onSave = launchSave,
            onLocalPrimaryAction = launchLocalPrimary,
            onOpenLocalModels = onNavigateToLocalModels,
            onApiSourceSelected = { manualApiSource = it },
            onTailnetPeerSelected = { peer ->
                com.promenar.nexara.data.tailnet.TailnetEndpoints.addressForPeer(peer, baseUrl)?.let { address ->
                    baseUrl = address
                    connectionTestState = ProviderConnectionTestState.Idle
                    connectionTestFailureMessage = null
                }
            },
            onOpenTailscaleSettings = onOpenTailscaleSettings,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderFormContent(
    state: ProviderFormUiState,
    actions: ProviderFormActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = when {
        state.isEditing && state.name.isNotBlank() -> state.name
        state.isEditing -> stringResource(R.string.provider_form_title_edit)
        else -> stringResource(R.string.provider_form_title_add)
    }
    NexaraSettingsPageLayout(
        title = title,
        onBack = actions.onBack,
        modifier = modifier,
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(PROVIDER_FORM_LIST_TAG),
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (state.unavailableLocalConfiguration) {
                    animatedFormItem("local-unavailable") {
                        ProviderFormErrorText(stringResource(R.string.local_inference_release_unavailable))
                    }
                }
                animatedFormItem("preset") {
                    NexaraSettingsSection(
                        title = stringResource(R.string.settings_batch1_connection_template),
                    ) { ProviderPresetSelector(state = state, actions = actions) }
                }
                if (state.legacyMigrationBlocked) {
                    animatedFormItem("legacy-migration") {
                        ProviderFormErrorText(
                            stringResource(R.string.provider_form_legacy_migration_required),
                        )
                    }
                }
                if (state.selectedPreset.name == "Custom") {
                    animatedFormItem("protocol") {
                        NexaraSettingsSection(title = stringResource(R.string.provider_form_protocol_type)) {
                            ProviderProtocolSelector(state = state, actions = actions)
                        }
                    }
                }
                if (state.isLocal) {
                    animatedFormItem("local-section") {
                        LocalProviderSection(state = state, actions = actions)
                    }
                } else {
                    state.apiSource?.let { source ->
                        animatedFormItem("api-source") {
                            ProviderApiSourceSection(
                                source = source,
                                tailnet = state.tailnetInfo,
                                onSourceSelected = actions.onApiSourceSelected,
                                onOpenTailscaleSettings = actions.onOpenTailscaleSettings,
                            )
                        }
                    }
                    animatedFormItem("cloud-section") {
                        CloudProviderSection(state = state, actions = actions)
                    }
                    when (state.connectionTestState) {
                        ProviderConnectionTestState.Success -> animatedFormItem("connection-status") {
                            ProviderFormStatusText(
                                stringResource(R.string.common_cd_success),
                                success = true,
                            )
                        }
                        ProviderConnectionTestState.Error -> animatedFormItem("connection-status") {
                            ProviderFormStatusText(
                                state.connectionTestFailureMessage
                                    ?: stringResource(R.string.common_cd_failed),
                                success = false,
                            )
                        }
                        ProviderConnectionTestState.Unavailable -> animatedFormItem("connection-status") {
                            ProviderFormStatusText(
                                stringResource(R.string.provider_form_connection_unavailable),
                                success = null,
                            )
                        }
                        else -> Unit
                    }
                }
                if (state.saveFailed) {
                    animatedFormItem("save-failed") { ProviderFormErrorText(stringResource(R.string.provider_form_save_failed)) }
                }
            }
            AnimatedVisibility(visible = !state.isLocal, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                ProviderFormActionsSection(
                    state = state,
                    actions = actions,
                    modifier = Modifier.padding(
                        start = NexaraSpacing.ScreenHorizontal,
                        top = NexaraSpacing.Small,
                        end = NexaraSpacing.ScreenHorizontal,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                )
            }
        }
    }
}

/** 表单中按条件出现的分组与提示：出现时淡入、其余分组平滑让位。 */
private fun LazyListScope.animatedFormItem(key: String, content: @Composable () -> Unit) =
    item(key) { Box(Modifier.animateItem()) { content() } }

@Composable
private fun ProviderProtocolSelector(state: ProviderFormUiState, actions: ProviderFormActions) {
    NexaraValueRow(
        title = stringResource(R.string.provider_form_protocol_type),
        value = state.localProtocol.displayName,
        onClick = { actions.onProtocolMenuExpandedChange(true) },
        modifier = Modifier.testTag("provider_protocol_picker"),
    )
    NexaraBottomSheet(
        show = state.protocolMenuExpanded,
        scrollContent = true,
        title = stringResource(R.string.provider_form_protocol_type),
        onDismiss = { actions.onProtocolMenuExpandedChange(false) },
    ) {
        CUSTOM_PROTOCOL_OPTIONS.forEach { protocol ->
            NexaraSettingsItem(
                horizontalPadding = 0.dp,
                title = protocol.displayName,
                subtitle = protocol.defaultPath.takeIf(String::isNotEmpty),
                subtitleIsIdentifier = true,
                onClick = {
                    actions.onProtocolSelected(protocol)
                    actions.onProtocolMenuExpandedChange(false)
                },
                role = Role.RadioButton,
                selected = protocol == state.localProtocol,
                trailingContent = {
                    androidx.compose.material3.RadioButton(selected = protocol == state.localProtocol, onClick = null)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPresetSelector(state: ProviderFormUiState, actions: ProviderFormActions) {
    ExposedDropdownMenuBox(
        expanded = state.presetMenuExpanded,
        onExpandedChange = actions.onPresetMenuExpandedChange,
    ) {
        NexaraValueRow(
            title = stringResource(R.string.provider_form_preset_title),
            value = state.selectedPreset.name,
            onClick = { actions.onPresetMenuExpandedChange(!state.presetMenuExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = state.presetMenuExpanded,
            onDismissRequest = { actions.onPresetMenuExpandedChange(false) },
        ) {
            state.availablePresets.forEach { preset ->
                val selected = preset == state.selectedPreset
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    leadingIcon = { ProviderPresetIcon(preset, selected) },
                    trailingIcon = {
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    onClick = { actions.onPresetSelected(preset) },
                )
            }
        }
    }
}

@Composable
private fun ProviderPresetIcon(preset: ProviderPreset, selected: Boolean) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    preset.iconRes?.let { iconId ->
        Icon(
            painter = painterResource(iconId),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    } ?: Icon(
        imageVector = Icons.Rounded.Psychology,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun CloudProviderSection(state: ProviderFormUiState, actions: ProviderFormActions) {
    NexaraSettingsSection(
        title = stringResource(R.string.provider_form_config_section),
        footer = stringResource(R.string.provider_form_secure_storage),
    ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            textStyle = LocalTextStyle.current.forInput(),
            value = state.name,
            onValueChange = actions.onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.provider_form_label_name)) },
            placeholder = { Text(stringResource(R.string.provider_form_placeholder_name)) },
            singleLine = true,
        )
        // 选 Tailscale 内网时先选设备，接口地址随之填好。
        if (state.apiSource?.selected == ProviderApiSource.TAILNET) {
            TailnetPeerPicker(
                peers = state.tailnetInfo.peers,
                baseUrl = state.baseUrl,
                onPeerSelected = actions.onTailnetPeerSelected,
            )
        }
        OutlinedTextField(
            textStyle = LocalTextStyle.current.forInput(),
            value = state.baseUrl,
            onValueChange = actions.onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.provider_form_label_url)) },
            placeholder = { Text(stringResource(R.string.provider_form_placeholder_url)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            isError = !state.endpointValid,
            supportingText = if (!state.endpointValid) {
                {
                    ProviderFormErrorText(
                        stringResource(
                            when {
                                state.protocolUnsupported -> R.string.provider_form_protocol_unsupported
                                state.apiSource?.hint == ProviderApiSourceHint.TAILNET_NEEDS_HTTPS ->
                                    R.string.provider_form_tailnet_https_required
                                else -> R.string.provider_form_https_required
                            },
                        ),
                    )
                }
            } else {
                null
            },
        )
        ProviderAddressHint(hint = state.apiSource?.hint, onSourceSelected = actions.onApiSourceSelected)
        SecretField(
            value = state.secretInput,
            onValueChange = actions.onSecretChange,
            hasStoredSecret = state.hasStoredCredential,
            onRevealRequest = actions.onRevealSecret,
            onClear = actions.onClearSecret,
            modifier = if (state.credentialKindMismatch) {
                Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
            } else {
                Modifier
            },
            placeholder = stringResource(
                if (state.usesVertexCredential) {
                    R.string.provider_form_paste_json
                } else {
                    R.string.provider_form_placeholder_api_key
                },
            ),
            label = stringResource(
                if (state.usesVertexCredential) {
                    R.string.provider_form_label_sa
                } else {
                    R.string.provider_form_label_api_key
                },
            ),
            supportingText = if (state.credentialKindMismatch) stringResource(R.string.provider_form_credential_kind_changed) else null,
            isError = state.credentialKindMismatch,
        )
    }
}
}

@Composable
private fun ProviderFormActionsSection(
    state: ProviderFormUiState,
    actions: ProviderFormActions,
    modifier: Modifier = Modifier,
) {
    val testing = state.connectionTestState == ProviderConnectionTestState.Testing
    val connectionStatusDescription = when (state.connectionTestState) {
        ProviderConnectionTestState.Idle -> null
        ProviderConnectionTestState.Testing -> stringResource(R.string.provider_form_testing)
        ProviderConnectionTestState.Success -> stringResource(R.string.common_cd_success)
        ProviderConnectionTestState.Unavailable -> stringResource(R.string.provider_form_connection_unavailable)
        ProviderConnectionTestState.Error -> stringResource(R.string.common_cd_failed)
    }
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            onClick = actions.onTestConnection,
            enabled = !testing && !state.isSaving && state.endpointValid &&
                !state.protocolUnsupported && !state.legacyMigrationBlocked &&
                !state.credentialKindMismatch,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .heightIn(min = 48.dp)
                .semantics {
                    connectionStatusDescription?.let { stateDescription = it }
                },
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                text = stringResource(
                    if (testing) R.string.provider_form_testing else R.string.provider_form_btn_test,
                ),
                modifier = if (testing) Modifier.padding(start = 8.dp) else Modifier,
            )
        }
        Button(
            onClick = actions.onSave,
            enabled = state.endpointValid && !state.protocolUnsupported &&
                !state.legacyMigrationBlocked && !state.credentialKindMismatch && !state.isSaving &&
                state.connectionTestState != ProviderConnectionTestState.Testing,
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = stringResource(
                    if (state.isSaving) R.string.provider_form_saving else R.string.provider_form_btn_save,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun LocalProviderSection(state: ProviderFormUiState, actions: ProviderFormActions) {
    NexaraSettingsSection(
        title = stringResource(R.string.settings_batch1_local_inference),
    ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.localConnectionFailed) {
            TextButton(
                onClick = actions.onLocalPrimaryAction,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.provider_form_btn_test))
            }
            ProviderFormErrorText(stringResource(R.string.onboarding_local_model_unavailable))
            Button(
                onClick = actions.onOpenLocalModels,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.local_models_title))
            }
        } else {
            Button(
                onClick = actions.onLocalPrimaryAction,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.onboardingMode && state.isExistingProvider) {
                            R.string.provider_form_btn_test
                        } else {
                            R.string.local_models_title
                        },
                    ),
                )
            }
        }
    }
}
}

@Composable
private fun ProviderFormErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@Composable
private fun ProviderFormStatusText(message: String, success: Boolean?) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = when (success) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

internal fun isSecureProviderEndpoint(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal fun isProviderEndpointAllowed(protocol: ProtocolType, value: String): Boolean =
    when {
        protocol is ProtocolType.Local -> true
        isRetiredProviderProtocol(protocol) -> false
        !isSecureProviderEndpoint(value) -> false
        else -> runCatching {
            when (protocol) {
                ProtocolType.Google_VertexAI -> ProviderEndpointResolver.resolve(
                    protocol = protocol,
                    configuredBaseUrl = value,
                    target = ProviderEndpointTarget.VertexInference(
                        projectId = "endpoint-check",
                        location = VERTEX_DEFAULT_LOCATION,
                        model = "endpoint-check",
                        streaming = false,
                    ),
                )
                else -> ProviderEndpointResolver.resolve(
                    protocol = protocol,
                    configuredBaseUrl = value,
                    operation = ProviderEndpointOperation.INFERENCE,
                )
            }
        }.isSuccess
    }

internal fun isRetiredProviderProtocol(protocol: ProtocolType): Boolean =
    protocol == ProtocolType.Cohere_Chat || protocol == ProtocolType.Yi_ZeroOne

internal fun localProviderModelsForConnection(state: SlotState): List<String> =
    state.modelName.trim().takeIf { state.isLoaded && it.isNotEmpty() }?.let(::listOf).orEmpty()

internal fun credentialUpdateForSecretInput(
    input: String,
    hasStoredCredential: Boolean,
): CredentialUpdate = when {
    input.isNotBlank() -> CredentialUpdate.Replace(input)
    hasStoredCredential -> CredentialUpdate.Preserve
    else -> CredentialUpdate.Clear
}
