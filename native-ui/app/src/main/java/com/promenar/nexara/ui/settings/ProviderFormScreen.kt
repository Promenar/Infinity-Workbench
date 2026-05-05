package com.promenar.nexara.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

data class ProviderPreset(
    val name: String,
    val iconUrl: String,
    val defaultBaseUrl: String,
    val protocolId: ProtocolId
)

val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", "https://logo.clearbit.com/openai.com", "https://api.openai.com/v1", ProtocolId.OPENAI),
    ProviderPreset("Anthropic", "https://logo.clearbit.com/anthropic.com", "https://api.anthropic.com", ProtocolId.ANTHROPIC),
    ProviderPreset("Gemini", "https://logo.clearbit.com/google.com", "https://generativelanguage.googleapis.com", ProtocolId.VERTEX_AI),
    ProviderPreset("DeepSeek", "https://logo.clearbit.com/deepseek.com", "https://api.deepseek.com/v1", ProtocolId.OPENAI),
    ProviderPreset("Groq", "https://logo.clearbit.com/groq.com", "https://api.groq.com/openai/v1", ProtocolId.OPENAI),
    ProviderPreset("Mistral", "https://logo.clearbit.com/mistral.ai", "https://api.mistral.ai/v1", ProtocolId.OPENAI),
    ProviderPreset("Together", "https://logo.clearbit.com/together.ai", "https://api.together.xyz/v1", ProtocolId.OPENAI),
    ProviderPreset("Cohere", "https://logo.clearbit.com/cohere.com", "https://api.cohere.ai/v1", ProtocolId.OPENAI),
    ProviderPreset("Fireworks", "https://logo.clearbit.com/fireworks.ai", "https://api.fireworks.ai/inference/v1", ProtocolId.OPENAI),
    ProviderPreset("Perplexity", "https://logo.clearbit.com/perplexity.ai", "https://api.perplexity.ai", ProtocolId.OPENAI),
    ProviderPreset("OpenRouter", "https://openrouter.ai/favicon.ico", "https://openrouter.ai/api/v1", ProtocolId.OPENAI),
    ProviderPreset("xAI", "https://logo.clearbit.com/x.ai", "https://api.x.ai/v1", ProtocolId.OPENAI),
    ProviderPreset("SiliconFlow", "https://logo.clearbit.com/siliconflow.cn", "https://api.siliconflow.cn/v1", ProtocolId.OPENAI),
    ProviderPreset("ByteDance", "https://logo.clearbit.com/volcengine.com", "https://ark.cn-beijing.volces.com/api/v3", ProtocolId.OPENAI),
    ProviderPreset("Baidu", "https://logo.clearbit.com/baidu.com", "https://qianfan.baidubce.com/v2", ProtocolId.OPENAI),
    ProviderPreset("Alibaba", "https://logo.clearbit.com/aliyun.com", "https://dashscope.aliyuncs.com/compatible-mode/v1", ProtocolId.OPENAI),
    ProviderPreset("Tencent", "https://logo.clearbit.com/tencent.com", "https://api.hunyuan.cloud.tencent.com/v1", ProtocolId.OPENAI),
    ProviderPreset("Custom", "", "", ProtocolId.OPENAI)
)

@Composable
fun ProviderFormScreen(
    providerId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToModels: () -> Unit = {},
    onSave: (protocolId: ProtocolId, baseUrl: String, apiKey: String, model: String, name: String?) -> Unit = { _, _, _, _, _ -> }
) {
    var name by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(PROVIDER_PRESETS[0]) }
    var baseUrl by remember { mutableStateOf(PROVIDER_PRESETS[0].defaultBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }

    val isEditing = providerId != null

    NexaraPageLayout(
        title = if (isEditing) stringResource(R.string.provider_form_title_edit) else stringResource(R.string.provider_form_title_add),
        onBack = onNavigateBack
    ) {
        Text(
            text = stringResource(R.string.provider_form_desc),
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.provider_form_preset_title),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(280.dp)
        ) {
            items(PROVIDER_PRESETS) { preset ->
                val isSelected = selectedPreset.name == preset.name
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
                        .clip(NexaraShapes.large)
                        .background(bgAlpha)
                        .border(1.dp, borderColor, NexaraShapes.large)
                        .clickable {
                            selectedPreset = preset
                            if (preset.name != "Custom") {
                                name = preset.name
                                baseUrl = preset.defaultBaseUrl
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (isSelected) NexaraColors.Primary.copy(alpha = 0.2f)
                                    else NexaraColors.SurfaceHigh,
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = NexaraColors.Primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else if (preset.iconUrl.isNotEmpty()) {
                                coil3.compose.AsyncImage(
                                    model = preset.iconUrl,
                                    contentDescription = preset.name,
                                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                                )
                            } else {
                                Text(
                                    text = preset.name.take(1),
                                    style = NexaraTypography.labelMedium.copy(fontSize = 14.sp),
                                    color = NexaraColors.OnSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = preset.name,
                            style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                            color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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
                    color = NexaraColors.OnSurface
                )

                LabeledField(label = stringResource(R.string.provider_form_label_name)) {
                    GlassInputField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(R.string.provider_form_placeholder_name)
                    )
                }

                LabeledField(label = stringResource(R.string.provider_form_label_url)) {
                    GlassInputField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        placeholder = stringResource(R.string.provider_form_placeholder_url)
                    )
                }

                LabeledField(
                    label = stringResource(R.string.provider_form_label_api_key),
                    trailingLabel = stringResource(R.string.provider_form_secure_storage)
                ) {
                    Box {
                        GlassInputField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = stringResource(R.string.provider_form_placeholder_api_key),
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.padding(end = 40.dp)
                        )
                        IconButton(
                            onClick = { apiKeyVisible = !apiKeyVisible },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (apiKeyVisible) stringResource(R.string.provider_form_cd_hide) else stringResource(R.string.provider_form_cd_show),
                                tint = NexaraColors.OnSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (selectedPreset.protocolId == ProtocolId.VERTEX_AI) {
                    LabeledField(label = stringResource(R.string.provider_form_label_sa)) {
                        GlassInputField(
                            value = "",
                            onValueChange = { json ->
                                try {
                                    val trimmed = json.trim()
                                    if (trimmed.startsWith("{")) {
                                        val projectMatch = Regex("\"project_id\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
                                        if (projectMatch != null) {
                                            name = projectMatch.groupValues[1].take(20)
                                        }
                                    }
                                } catch (_: Exception) { }
                            },
                            placeholder = stringResource(R.string.provider_form_paste_json)
                        )
                    }
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
                    .clickable(enabled = !isTesting) {
                        isTesting = true
                        testStatus = null
                        // Simulate network test
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            isTesting = false
                            if (baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                                testStatus = true
                            } else {
                                testStatus = false
                            }
                            
                            // Reset test status after 2 seconds
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                testStatus = null
                            }, 2000)
                        }, 1500)
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
                        contentDescription = "Success",
                        tint = NexaraColors.StatusSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (testStatus == false) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Failed",
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
                    .clickable {
                        onSave(selectedPreset.protocolId, baseUrl, apiKey, "gpt-4o", name.ifBlank { null })
                        if (providerId != null) {
                            onNavigateToModels()
                        } else {
                            onNavigateBack()
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
                        text = stringResource(R.string.provider_form_btn_save),
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
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
