package com.promenar.nexara.ui.settings

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.local.inference.SlotState
import com.promenar.nexara.data.remote.protocol.ProtocolFactory
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.model.ProviderSummary
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch

data class ProviderPreset(
    val name: String,
    val protocolType: ProtocolType,
    val defaultBaseUrl: String,
    val iconRes: Int? = null
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
            if (protocolType is ProtocolType.Google_VertexAI) summary.hasVertexCredentials else summary.hasApiKey
        ) { "保存后的提供商凭证不可用" }
        CredentialUpdate.Clear -> check(!summary.hasApiKey && !summary.hasVertexCredentials) {
            "提供商凭证清除未生效"
        }
        CredentialUpdate.Preserve -> Unit
    }
    return savedProviderId
}

val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", R.drawable.ic_provider_openai),
    ProviderPreset("DeepSeek", ProtocolType.DeepSeek, "https://api.deepseek.com", R.drawable.ic_provider_deepseek),
    ProviderPreset("Anthropic", ProtocolType.Anthropic_Messages, "https://api.anthropic.com", R.drawable.ic_provider_anthropic),
    ProviderPreset("Gemini", ProtocolType.Google_VertexAI, "https://generativelanguage.googleapis.com", R.drawable.ic_provider_gemini),
    ProviderPreset("Kimi", ProtocolType.Moonshot_Kimi, "https://api.moonshot.cn", R.drawable.ic_provider_kimi),
    ProviderPreset("通义千问", ProtocolType.Qwen_DashScope, "https://dashscope.aliyuncs.com/compatible-mode", R.drawable.ic_provider_qwen),
    ProviderPreset("智谱", ProtocolType.Zhipu_GLM, "https://open.bigmodel.cn/api/paas", R.drawable.ic_provider_zhipu),
    ProviderPreset("豆包", ProtocolType.Doubao_ByteDance, "https://ark.cn-beijing.volces.com/api", R.drawable.ic_provider_doubao),
    ProviderPreset("零一万物", ProtocolType.Yi_ZeroOne, "https://api.lingyiwanwu.com", R.drawable.ic_provider_yi),
    ProviderPreset("百川", ProtocolType.Baichuan, "https://api.baichuan-ai.com", R.drawable.ic_provider_baichuan),
    ProviderPreset("Mistral", ProtocolType.Mistral_Chat, "https://api.mistral.ai", R.drawable.ic_provider_mistral),
    ProviderPreset("Cohere", ProtocolType.Cohere_Chat, "https://api.cohere.ai", R.drawable.ic_provider_cohere),
    ProviderPreset("Local", ProtocolType.Local, "", R.drawable.ic_provider_local),
    ProviderPreset("Custom", ProtocolType.Generic_OpenAI_Compat, "", R.drawable.ic_provider_custom)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderFormScreen(
    providerId: String? = null,
    forceLocalProbeFailureForTesting: Boolean = false,
    onNavigateBack: () -> Unit,
    onNavigateToModels: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onboardingMode: Boolean = false,
    onSaved: (String) -> Unit = {},
    onConnectionVerified: (String) -> Unit = {},
    onSave: suspend (protocolType: ProtocolType, baseUrl: String, credential: CredentialUpdate, model: String, name: String?) -> String = { _, _, _, _, _ -> "" },
) {
    val context = LocalContext.current
    val app = context.applicationContext as NexaraApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(app)
    )
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
    var baseUrl by remember { mutableStateOf(PROVIDER_PRESETS[0].defaultBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var credentialUpdate by remember { mutableStateOf<CredentialUpdate>(CredentialUpdate.Preserve) }
    var hasCredential by remember { mutableStateOf(false) }
    var originalUsesVertex by remember { mutableStateOf<Boolean?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var localConnectionFailed by remember { mutableStateOf(false) }
    var unavailableLocalConfiguration by remember { mutableStateOf(false) }
    var localProto by remember { mutableStateOf<ProtocolType>(ProtocolType.Generic_OpenAI_Compat) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(providerId) {
        if (providerId != null) {
            val config = if (forceLocalProbeFailureForTesting) {
                com.promenar.nexara.data.model.ProviderSummary(
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
                val matched = availableProviderPresets.find {
                    it.protocolType == config.protocolType && (it.name != "Custom" || config.protocolType == ProtocolType.Generic_OpenAI_Compat)
                }
                selectedPreset = matched ?: availableProviderPresets.last()
                if (selectedPreset.name == "Custom") {
                    localProto = config.protocolType.takeUnless {
                        it is ProtocolType.Local && !app.localInferenceRuntimeGate.isAvailable
                    } ?: ProtocolType.Generic_OpenAI_Compat
                }
            }
        }
    }

    val isEditing = providerId != null
    val effectiveProtocol = if (selectedPreset.name == "Custom") localProto else selectedPreset.protocolType
    val isLocal = effectiveProtocol is ProtocolType.Local
    val endpointValid = isProviderEndpointAllowed(effectiveProtocol, baseUrl)
    val selectedUsesVertex = effectiveProtocol is ProtocolType.Google_VertexAI
    val credentialKindMismatch = hasCredential && credentialUpdate is CredentialUpdate.Preserve &&
        originalUsesVertex != null && originalUsesVertex != selectedUsesVertex

    // 键盘避让：当任意配置字段获取焦点时，将 "Configuration" 标题带入视野
    val bringIntoView = remember { BringIntoViewRequester() }
    var focusTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            bringIntoView.bringIntoView()
        }
    }

    NexaraPageLayout(
        title = when {
            isEditing && name.isNotBlank() -> name
            isEditing -> stringResource(R.string.provider_form_title_edit)
            else -> stringResource(R.string.provider_form_title_add)
        },
        onBack = onNavigateBack
    ) {
        Text(
            text = stringResource(R.string.provider_form_desc),
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )

        if (unavailableLocalConfiguration) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.local_inference_release_unavailable),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.Error,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.provider_form_preset_title),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = presetMenuExpanded,
            onExpandedChange = { presetMenuExpanded = it }
        ) {
            // 收起态：玻璃风格卡片（图标 + 名称 + 下拉箭头），与 GlassInputField 协调
            // 注意：不可在此 Box 上再加 .clickable —— menuAnchor() 内部的 expandable 已负责
            // 点击展开（通过 onExpandedChange 回调），叠加 clickable 会与 expandable 的点击
            // 处理产生竞争，导致菜单弹不出来。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NexaraShapes.medium)
                    .background(NexaraColors.SurfaceContainer)
                    .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    selectedPreset.iconRes?.let { iconId ->
                        Icon(
                            painter = painterResource(id = iconId),
                            contentDescription = null,
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    } ?: run {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = null,
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = selectedPreset.name,
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.weight(1f)
                    )

                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .alpha(if (presetMenuExpanded) 1f else 0.6f)
                    )
                }
            }

            // 展开态：使用 ExposedDropdownMenuBoxScope 的 ExposedDropdownMenu
            // 它内部用专用的 PositionProvider 锚定到上面的 menuAnchor，并应用 exposedDropdownSize
            // 约束；自带 scrollState 处理 14 项的滚动，无需手动 verticalScroll
            ExposedDropdownMenu(
                expanded = presetMenuExpanded,
                onDismissRequest = { presetMenuExpanded = false }
            ) {
                availableProviderPresets.forEach { preset ->
                    val isSelected = selectedPreset.name == preset.name
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                preset.iconRes?.let { iconId ->
                                    Icon(
                                        painter = painterResource(id = iconId),
                                        contentDescription = null,
                                        tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } ?: run {
                                    Icon(
                                        imageVector = Icons.Rounded.Psychology,
                                        contentDescription = null,
                                        tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = preset.name,
                                    style = NexaraTypography.bodyMedium,
                                    color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface
                                )
                            }
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = NexaraColors.Primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        onClick = {
                            selectedPreset = preset
                            if (preset.name != "Custom" && preset.name != "Local") {
                                name = preset.name
                                baseUrl = preset.defaultBaseUrl
                            }
                            presetMenuExpanded = false
                        }
                    )
                }
            }
        }

        if (selectedPreset.name == "Custom") {
            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.provider_form_protocol_type), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
            Spacer(modifier = Modifier.height(12.dp))
            com.promenar.nexara.ui.common.ProtocolSelector(
                selected = localProto,
                onSelect = { localProto = it }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLocal) {
            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.local_models_desc),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(NexaraShapes.medium)
                            .background(NexaraColors.InversePrimary)
                            .clickable {
                                if (!isSaving) scope.launch {
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
                                    } catch (_: Exception) {
                                        saveFailed = true
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                if (onboardingMode && providerId != null) {
                                    R.string.provider_form_btn_test
                                } else {
                                    R.string.local_models_title
                                }
                            ),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnPrimary
                        )
                    }
                    if (localConnectionFailed) {
                        Text(
                            text = stringResource(R.string.onboarding_local_model_unavailable),
                            style = NexaraTypography.bodyMedium,
                            color = NexaraColors.Error,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(NexaraShapes.medium)
                                .border(0.5.dp, NexaraColors.Primary, NexaraShapes.medium)
                                .clickable(onClick = onNavigateToLocalModels)
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.local_models_title),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary,
                            )
                        }
                    }
                }
            }
        } else {
        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaraShapes.large as RoundedCornerShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.provider_form_config_section),
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.bringIntoViewRequester(bringIntoView).focusable()
                )

                LabeledField(label = stringResource(R.string.provider_form_label_name)) {
                    GlassInputField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(R.string.provider_form_placeholder_name),
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusTrigger++ }
                    )
                }

                LabeledField(label = stringResource(R.string.provider_form_label_url)) {
                    GlassInputField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        placeholder = stringResource(R.string.provider_form_placeholder_url),
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusTrigger++ }
                    )
                }
                if (!endpointValid) {
                    Text(
                        text = stringResource(R.string.provider_form_https_required),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.Error,
                    )
                }
                if (credentialKindMismatch) {
                    Text(
                        text = stringResource(R.string.provider_form_credential_kind_changed),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.Error,
                    )
                }

                LabeledField(
                    label = stringResource(
                        if (effectiveProtocol is ProtocolType.Google_VertexAI) {
                            R.string.provider_form_label_sa
                        } else R.string.provider_form_label_api_key,
                    ),
                    trailingLabel = stringResource(R.string.provider_form_secure_storage)
                ) {
                    SecretField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            credentialUpdate = if (it.isBlank()) CredentialUpdate.Preserve else CredentialUpdate.Replace(it)
                        },
                        hasStoredSecret = hasCredential,
                        onRevealRequest = {
                            providerId?.let {
                                viewModel.revealProviderCredential(
                                    it,
                                    effectiveProtocol is ProtocolType.Google_VertexAI,
                                )
                            }
                        },
                        onClear = {
                            apiKey = ""
                            hasCredential = false
                            credentialUpdate = CredentialUpdate.Clear
                        },
                        placeholder = stringResource(
                            if (effectiveProtocol is ProtocolType.Google_VertexAI) {
                                R.string.provider_form_paste_json
                            } else R.string.provider_form_placeholder_api_key,
                        ),
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusTrigger++ },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        var testStatus by remember { mutableStateOf<Boolean?>(null) }
        var isTesting by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(NexaraShapes.medium)
                    .background(if (testStatus == true) NexaraColors.StatusSuccess.copy(alpha = 0.1f) else if (testStatus == false) NexaraColors.StatusError.copy(alpha = 0.1f) else NexaraColors.SurfaceHigh)
                    .border(0.5.dp, if (testStatus == true) NexaraColors.StatusSuccess else if (testStatus == false) NexaraColors.StatusError else NexaraColors.Primary.copy(alpha = 0.3f), NexaraShapes.medium)
                    .clickable(enabled = !isTesting && !isSaving && endpointValid && !credentialKindMismatch) {
                        isTesting = true
                        testStatus = null
                        scope.launch {
                            val protocolType = if (selectedPreset.name == "Custom") localProto else selectedPreset.protocolType
                            testStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    if (protocolType is ProtocolType.Local) {
                                        true
                                    } else {
                                        val transient = when (val update = credentialUpdate) {
                                            is CredentialUpdate.Replace -> update.value.toCharArray()
                                            CredentialUpdate.Clear -> CharArray(0)
                                            CredentialUpdate.Preserve -> providerId?.let {
                                                viewModel.revealProviderCredential(
                                                    it,
                                                    protocolType is ProtocolType.Google_VertexAI,
                                                )
                                            } ?: CharArray(0)
                                        }
                                        try {
                                            ProtocolFactory.create(
                                                type = protocolType,
                                                baseUrl = baseUrl,
                                                apiKey = transient.concatToString(),
                                                model = "",
                                            ).listModels().isNotEmpty()
                                        } finally {
                                            transient.fill('\u0000')
                                        }
                                    }
                                }.getOrDefault(false)
                            }
                            if (testStatus == true && providerId != null) {
                                isSaving = true
                                saveFailed = false
                                try {
                                    val savedProviderId = persistVerifiedProviderConnection(
                                        protocolType = protocolType,
                                        baseUrl = baseUrl,
                                        credential = credentialUpdate,
                                        model = "",
                                        name = name.ifBlank { null },
                                        onSave = onSave,
                                        readSummary = viewModel::getProviderSummary,
                                    )
                                    onConnectionVerified(savedProviderId)
                                    if (onboardingMode) onNavigateBack()
                                } catch (_: Exception) {
                                    testStatus = false
                                    saveFailed = true
                                } finally {
                                    isSaving = false
                                }
                            }
                            isTesting = false
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isTesting) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = NexaraColors.Primary,
                        strokeWidth = 2.dp
                    )
                } else if (testStatus == true) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.common_cd_success),
                        tint = NexaraColors.StatusSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (testStatus == false) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_cd_failed),
                        tint = NexaraColors.StatusError,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.provider_form_btn_test),
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.Primary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(NexaraShapes.medium)
                    .background(NexaraColors.InversePrimary)
                    .clickable(enabled = endpointValid && !credentialKindMismatch && !isSaving) {
                        isSaving = true
                        saveFailed = false
                        scope.launch {
                            try {
                                val savedProviderId = onSave(
                                    if (selectedPreset.name == "Custom") localProto else selectedPreset.protocolType,
                                    baseUrl,
                                    credentialUpdate,
                                    "",
                                    name.ifBlank { null },
                                )
                                onSaved(savedProviderId)
                                if (onboardingMode) {
                                    onNavigateBack()
                                } else {
                                    if (providerId != null) onNavigateToModels() else onNavigateBack()
                                }
                            } catch (_: Exception) {
                                saveFailed = true
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = null,
                        tint = NexaraColors.OnPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(if (isSaving) R.string.provider_form_saving else R.string.provider_form_btn_save),
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnPrimary
                    )
                }
            }
        }
        if (saveFailed) {
            Text(
                text = stringResource(R.string.provider_form_save_failed),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.Error,
            )
        }

        }

        // 键盘避让底部留白 — 确保键盘弹起时用户可滚动查看全部 3 行配置字段
        Spacer(modifier = Modifier.height(200.dp))
    }
}

internal fun isSecureProviderEndpoint(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal fun isProviderEndpointAllowed(protocol: ProtocolType, value: String): Boolean =
    protocol is ProtocolType.Local || isSecureProviderEndpoint(value)

internal fun localProviderModelsForConnection(state: SlotState): List<String> =
    state.modelName.trim().takeIf { state.isLoaded && it.isNotEmpty() }?.let(::listOf).orEmpty()

@Composable
private fun PresetItem(
    preset: ProviderPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) NexaraColors.Primary else NexaraColors.GlassBorder,
        animationSpec = tween(200),
        label = "presetBorder"
    )
    val bgAlpha by animateColorAsState(
        targetValue = if (isSelected) NexaraColors.Primary.copy(alpha = 0.1f) else NexaraColors.SurfaceContainer.copy(alpha = 0.3f),
        animationSpec = tween(200),
        label = "presetBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.large)
            .background(bgAlpha)
            .border(1.dp, borderColor, NexaraShapes.large)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (isSelected) NexaraColors.Primary.copy(alpha = 0.2f)
                        else NexaraColors.SurfaceHigh,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                preset.iconRes?.let { iconId ->
                    Icon(
                        painter = painterResource(id = iconId),
                        contentDescription = null,
                        tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                } ?: run {
                    Icon(
                        imageVector = Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = preset.name,
                style = NexaraTypography.bodyLarge,
                color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    trailingLabel: String? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (trailingLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Text(
                    text = trailingLabel,
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            Text(
                text = label,
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant
            )
        }
        content()
    }
}

@Composable
private fun GlassInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceContainer)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = NexaraTypography.bodyMedium.copy(
                color = NexaraColors.OnSurface
            ),
            cursorBrush = SolidColor(NexaraColors.Primary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth()
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
