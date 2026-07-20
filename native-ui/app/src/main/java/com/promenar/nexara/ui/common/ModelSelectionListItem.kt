package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.SupportState

@Composable
private fun ModelCapability.selectionLabel(): String? = when (this) {
    ModelCapability.REASONING -> stringResource(R.string.provider_models_type_reasoning)
    ModelCapability.VISION_INPUT -> stringResource(R.string.provider_models_capability_vision)
    ModelCapability.AUDIO_INPUT -> stringResource(R.string.provider_models_capability_audio_input)
    ModelCapability.AUDIO_OUTPUT -> stringResource(R.string.provider_models_capability_audio_output)
    ModelCapability.VIDEO_INPUT -> stringResource(R.string.provider_models_capability_video)
    ModelCapability.TOOL_CALLING -> stringResource(R.string.sheet_tab_tools)
    ModelCapability.STRUCTURED_OUTPUT -> stringResource(R.string.provider_models_capability_structured_output)
    ModelCapability.PROMPT_CACHING -> stringResource(R.string.provider_models_capability_prompt_caching)
    ModelCapability.COMPUTER_USE -> stringResource(R.string.provider_models_capability_computer_use)
    ModelCapability.WEB_ACCESS -> stringResource(R.string.provider_models_capability_internet)
    ModelCapability.CHAT_ENDPOINT -> null
}

@Composable
fun ModelSelectionListItem(
    model: ModelSelectionUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val supportedCapabilities = model.capabilityStates
        .filter { it.value == SupportState.SUPPORTED }
        .keys
        .take(2)
    val capabilitySummary = supportedCapabilities.mapNotNull { it.selectionLabel() }
    val contextSummary = model.contextTokens?.let { tokens ->
        when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}${stringResource(R.string.common_model_picker_m_context)}"
            tokens >= 1_000 -> "${tokens / 1_000}${stringResource(R.string.common_model_picker_k_context)}"
            else -> tokens.toString()
        }
    }

    ListItem(
        headlineContent = {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = listOfNotNull(model.providerName, contextSummary).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capabilitySummary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = capabilitySummary.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics {
                this.selected = selected
            }
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
    )
}
