package com.promenar.infinity.ui.rag

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.common.ModelSelectionListItem
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.matchesModelPickerFilter
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.common.toModelSelectionUiModel

/**
 * 图谱抽取、查询改写这类“可跟随快速模型”的模型行：值行就地弹出单选面板，
 * 第一项“跟随快速模型”写入 null，其余是可走对话接口的已启用模型。
 */
@Composable
internal fun KnowledgeModelRow(
    title: String,
    modelId: String?,
    models: List<ModelInfo>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    testTagPrefix: String,
) {
    var show by remember { mutableStateOf(false) }
    val anchor = rememberInfinityPopoverAnchor()
    InfinityValueRow(
        title = title,
        value = knowledgeModelLabel(modelId, models) ?: stringResource(R.string.settings_ia_model_follow_fast),
        onClick = { if (!anchor.consumeRecentDismiss()) show = true },
        icon = icon,
        enabled = enabled,
        modifier = modifier.infinityPopoverAnchor(anchor).testTag("${testTagPrefix}_row"),
    )
    val candidates = remember(models) {
        models.filter { it.enabled }
            .map { it.toModelSelectionUiModel() }
            .filter { it.matchesModelPickerFilter("chat") }
    }
    InfinityBottomSheet(
        show = show,
        onDismiss = { show = false },
        title = title,
        scrollContent = true,
        popover = InfinityPopoverSpec(anchor),
    ) {
        val following = modelId.isNullOrBlank()
        InfinitySettingsItem(
            horizontalPadding = 0.dp,
            title = stringResource(R.string.settings_ia_model_follow_fast),
            subtitle = stringResource(R.string.settings_ia_model_follow_fast_desc),
            role = Role.RadioButton,
            selected = following,
            onClick = { onSelect(null); show = false },
            modifier = Modifier.testTag("${testTagPrefix}_follow"),
            trailingContent = {
                if (following) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            },
        )
        candidates.forEach { model ->
            ModelSelectionListItem(
                model = model,
                selected = model.selectionId == modelId,
                onClick = { onSelect(model.selectionId); show = false },
            )
        }
    }
}
