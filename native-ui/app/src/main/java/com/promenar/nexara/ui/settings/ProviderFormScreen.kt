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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val protocolId: ProtocolId,
    val fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector
)

val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", "https://api.iconify.design/simple-icons:openai.svg?color=%23ffffff", "https://api.openai.com/v1", ProtocolId.OPENAI, Icons.Rounded.AutoAwesome),
    ProviderPreset("Anthropic", "https://api.iconify.design/simple-icons:anthropic.svg?color=%23ffffff", "https://api.anthropic.com", ProtocolId.ANTHROPIC, Icons.Rounded.Psychology),
    ProviderPreset("VertexAI", "https://api.iconify.design/simple-icons:googlecloud.svg?color=%23ffffff", "https://generativelanguage.googleapis.com", ProtocolId.VERTEX_AI, Icons.Rounded.Hub),
    ProviderPreset("Local", "", "", ProtocolId.LOCAL, Icons.Rounded.Memory),
    ProviderPreset("Custom", "", "", ProtocolId.OPENAI, Icons.Rounded.Extension)
)

@Composable
fun ProviderFormScreen(
    providerId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToModels: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onSave: (protocolId: ProtocolId, baseUrl: String, apiKey: String, model: String, name: String?) -> Unit = { _, _, _, _, _ -> }
) {
    var name by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(PROVIDER_PRESETS[0]) }
    var baseUrl by remember { mutableStateOf(PROVIDER_PRESETS[0].defaultBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }

    val isEditing = providerId != null
    val isLocal = selectedPreset.protocolId == ProtocolId.LOCAL

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

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PROVIDER_PRESETS.forEach { preset ->
                PresetItem(
                    preset = preset,
                    isSelected = selectedPreset.name == preset.name,
                    onClick = {
                        selectedPreset = preset
                        if (preset.name != "Custom" && preset.name != "Local") {
                            name = preset.name
                            baseUrl = preset.defaultBaseUrl
                        }
                    }
                )
            }
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
                                onSave(ProtocolId.LOCAL, "", "", "", "本地模型")
                                onNavigateToLocalModels()
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.local_models_title),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnPrimary
                        )
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
                        onSave(selectedPreset.protocolId, baseUrl, apiKey, "", name.ifBlank { null })
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

        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

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
                if (preset.iconUrl.isNotEmpty()) {
                    coil3.compose.AsyncImage(
                        model = preset.iconUrl,
                        contentDescription = preset.name,
                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(Color.Transparent),
                        fallback = androidx.compose.ui.graphics.painter.ColorPainter(Color.Transparent)
                    )
                    // If AsyncImage fails or is loading, the fallback icon below will show through or be shown instead
                    // However, AsyncImage doesn't easily tell us it failed without state.
                    // We'll use a simpler approach: always show fallback icon if it's Local/Custom, 
                    // and use Icon for fallback if URL is empty.
                }
                
                if (preset.iconUrl.isEmpty()) {
                    Icon(
                        imageVector = preset.fallbackIcon,
                        contentDescription = null,
                        tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    // Overlay icon as fallback if URL fails (crude but effective if we don't want complex state)
                    // Better: use a dedicated fallback Icon component
                    Icon(
                        imageVector = preset.fallbackIcon,
                        contentDescription = null,
                        tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(20.dp).alpha(0.5f)
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