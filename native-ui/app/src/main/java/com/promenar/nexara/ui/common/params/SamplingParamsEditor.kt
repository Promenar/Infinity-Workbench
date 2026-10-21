package com.promenar.nexara.ui.common.params

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.params.MODEL_DEFAULT_SCHEME_ID
import com.promenar.nexara.data.model.params.ParameterScheme
import com.promenar.nexara.data.model.params.SamplingParam
import com.promenar.nexara.data.model.params.SamplingValues
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraValuePickerContent
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.NumericSettingKind
import com.promenar.nexara.ui.common.NumericSettingSpec
import com.promenar.nexara.ui.common.PickerRestoreAction
import com.promenar.nexara.ui.theme.forInput

/** 参数值来自哪一层：本层写入、沿用上一层（会话沿用助手），或不发送、由模型按默认处理。 */
enum class ParamSource { OWN, INHERITED, DEFAULT }

/** 方案行的选中状态：[schemeId] 为方案 id 或 [MODEL_DEFAULT_SCHEME_ID]，null 表示当前取值不对应任何方案。 */
data class SchemeSelection(val schemeId: String?, val modified: Boolean)

/**
 * 采样参数编辑器的数据。[own] 是本层写入的值，[inherited] 是上一层的值；
 * [followsScheme] 为真时本层完全决定采样参数，不再沿用上一层。
 */
@Immutable
data class SamplingEditorState(
    val own: SamplingValues,
    val inherited: SamplingValues = SamplingValues(),
    val followsScheme: Boolean = false,
    /** 最近一次选用的方案；只用于判断“已修改”，不影响发送的值。 */
    val baseSchemeId: String? = null,
    /** 当前模型会发送的参数，按显示顺序。 */
    val visible: List<SamplingParam>,
    /** 当前模型不使用的参数，在底部说明。 */
    val hidden: List<SamplingParam> = emptyList(),
    val schemes: List<ParameterScheme> = emptyList(),
) {
    val effective: SamplingValues get() = if (followsScheme) own else own.orElse(inherited)

    /** 方案只保存当前模型会发送的参数。 */
    val savableValues: SamplingValues get() = effective.onlyFor(visible.toSet())

    fun source(param: SamplingParam): ParamSource = when {
        own.valueOf(param) != null -> ParamSource.OWN
        !followsScheme && inherited.valueOf(param) != null -> ParamSource.INHERITED
        else -> ParamSource.DEFAULT
    }

    val selection: SchemeSelection get() = resolveSchemeSelection(this)
}

/**
 * 选中刚选用的方案并标出之后是否改过；没有可用的基准方案时，按当前取值匹配“模型默认”或某个已存方案。
 * 只比较当前模型会发送的参数，隐藏项的残留值不算修改。
 */
fun resolveSchemeSelection(state: SamplingEditorState): SchemeSelection {
    val current = state.savableValues
    val visible = state.visible.toSet()
    when (val base = state.baseSchemeId) {
        MODEL_DEFAULT_SCHEME_ID -> return SchemeSelection(base, !current.isModelDefault)
        null -> Unit
        else -> state.schemes.firstOrNull { it.id == base }?.let { scheme ->
            return SchemeSelection(scheme.id, current != scheme.values.onlyFor(visible))
        }
    }
    if (current.isModelDefault) return SchemeSelection(MODEL_DEFAULT_SCHEME_ID, false)
    state.schemes.firstOrNull { it.values.onlyFor(visible) == current }?.let { return SchemeSelection(it.id, false) }
    return SchemeSelection(null, false)
}

class SamplingEditorActions(
    val onApplyModelDefault: () -> Unit = {},
    val onApplyScheme: (ParameterScheme) -> Unit = {},
    /** null 清空本层设置。 */
    val onParamChange: (SamplingParam, Double?) -> Unit = { _, _ -> },
    val onSaveScheme: (String) -> Unit = {},
    val onOverwriteScheme: (ParameterScheme) -> Unit = {},
    val onRenameScheme: (ParameterScheme, String) -> Unit = { _, _ -> },
    val onDeleteScheme: (ParameterScheme) -> Unit = {},
)

/** 方案行：模型默认、本模型的已存方案和“存为方案”；修改过选中的方案时给出覆盖与另存。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParameterSchemeRow(
    state: SamplingEditorState,
    actions: SamplingEditorActions,
    modelName: String?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalNexaraHaptics.current
    val selection = state.selection
    var naming by remember { mutableStateOf<SchemeNaming?>(null) }
    androidx.compose.foundation.layout.Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SchemeChip(
                label = stringResource(R.string.params_scheme_model_default),
                selected = selection.schemeId == MODEL_DEFAULT_SCHEME_ID,
                tag = "params_scheme_model_default",
                onClick = {
                    haptics.select(selection.schemeId == MODEL_DEFAULT_SCHEME_ID && !selection.modified)
                    actions.onApplyModelDefault()
                },
            )
            state.schemes.forEach { scheme ->
                SchemeChip(
                    label = scheme.name,
                    selected = selection.schemeId == scheme.id,
                    tag = "params_scheme_${scheme.id}",
                    onClick = {
                        haptics.select(selection.schemeId == scheme.id && !selection.modified)
                        actions.onApplyScheme(scheme)
                    },
                    menu = { dismiss ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.params_scheme_rename)) }, onClick = {
                            dismiss(); naming = SchemeNaming.Rename(scheme)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.params_scheme_delete), color = MaterialTheme.colorScheme.error) }, onClick = {
                            dismiss(); actions.onDeleteScheme(scheme)
                        })
                    },
                )
            }
            if (modelName != null && selection.schemeId == null) {
                AssistChip(
                    onClick = { naming = SchemeNaming.Create },
                    label = { Text(stringResource(R.string.params_scheme_save)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("params_scheme_save"),
                )
            }
        }
        val selectedScheme = state.schemes.firstOrNull { it.id == selection.schemeId }
        if (selection.modified && modelName != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.params_scheme_modified,
                        selectedScheme?.name ?: stringResource(R.string.params_scheme_model_default),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectedScheme != null) {
                    TextButton(onClick = { actions.onOverwriteScheme(selectedScheme) }, modifier = Modifier.testTag("params_scheme_overwrite")) {
                        Text(stringResource(R.string.params_scheme_overwrite))
                    }
                }
                TextButton(onClick = { naming = SchemeNaming.Create }, modifier = Modifier.testTag("params_scheme_save_as")) {
                    Text(stringResource(if (selectedScheme != null) R.string.params_scheme_save_as else R.string.params_scheme_save))
                }
            }
        }
        Text(
            text = modelName?.let { stringResource(R.string.params_scheme_scope, it) } ?: stringResource(R.string.params_no_model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    naming?.let { current ->
        SchemeNameDialog(
            initial = (current as? SchemeNaming.Rename)?.scheme?.name.orEmpty(),
            onDismiss = { naming = null },
            onConfirm = { name ->
                naming = null
                when (current) {
                    SchemeNaming.Create -> actions.onSaveScheme(name)
                    is SchemeNaming.Rename -> actions.onRenameScheme(current.scheme, name)
                }
            },
        )
    }
}

private sealed interface SchemeNaming {
    data object Create : SchemeNaming
    data class Rename(val scheme: ParameterScheme) : SchemeNaming
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SchemeChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = if (selected) {
                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null,
            modifier = Modifier.testTag(tag),
        )
        if (menu != null) {
            // 长按打开管理菜单；覆盖在胶囊上，点按仍交给胶囊本身。
            Box(
                Modifier.matchParentSize().combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                ),
            )
            NexaraDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menu { menuOpen = false }
            }
        }
    }
}

@Composable
private fun SchemeNameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.params_scheme_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(SCHEME_NAME_MAX_LENGTH) },
                textStyle = LocalTextStyle.current.forInput(),
                placeholder = { Text(stringResource(R.string.params_scheme_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("params_scheme_name_input"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("params_scheme_name_confirm")) {
                Text(stringResource(R.string.common_btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_btn_cancel)) }
        },
    )
}

private const val SCHEME_NAME_MAX_LENGTH = 24

/**
 * 逐项参数行。值来自本层时显示 [ownLabel]（如“本会话”），沿用上一层时显示“沿用助手”，
 * 未设置时显示“默认”；弹层里可一键恢复为不设置。
 */
@Composable
fun SamplingParamRows(state: SamplingEditorState, actions: SamplingEditorActions, ownLabel: String?) {
    val inheritedLabel = stringResource(R.string.params_source_agent)
    val defaultLabel = stringResource(R.string.params_value_default)
    val modelDefaultLabel = stringResource(R.string.params_use_model_default)
    val agentSettingLabel = stringResource(R.string.params_restore_agent)
    state.visible.forEach { param ->
        val spec = samplingParamSpec(param)
        val source = state.source(param)
        val value = state.effective.valueOf(param)?.toDouble()
        val title = samplingParamTitle(param)
        var show by rememberSaveable(param) { mutableStateOf(false) }
        NexaraValueRow(
            title = title,
            value = value?.let(spec::format) ?: defaultLabel,
            subtitle = when (source) {
                ParamSource.OWN -> ownLabel
                ParamSource.INHERITED -> inheritedLabel
                ParamSource.DEFAULT -> null
            },
            onClick = { show = true },
            modifier = Modifier
                .semantics { if (value == null) stateDescription = defaultLabel }
                .testTag("params_row_${param.name.lowercase()}"),
        )
        NexaraBottomSheet(show = show, onDismiss = { show = false }) {
            NexaraValuePickerContent(
                title = title,
                value = value ?: spec.defaultValue,
                spec = spec,
                onValueChange = { actions.onParamChange(param, it) },
                onDismiss = { show = false },
                // 本层清空后若会回到上一层的值，按钮写明“沿用助手设置”；沿用中的值清空则改为模型默认。
                restoreDefault = when {
                    // 尚未设置：弹层不勾选任何预设，“使用模型默认”一项即当前状态。
                    source == ParamSource.DEFAULT -> PickerRestoreAction(modelDefaultLabel, activeLabel = defaultLabel) {}
                    source == ParamSource.OWN && !state.followsScheme && state.inherited.valueOf(param) != null ->
                        PickerRestoreAction(agentSettingLabel) { actions.onParamChange(param, null) }
                    else -> PickerRestoreAction(modelDefaultLabel) { actions.onParamChange(param, null) }
                },
            )
        }
    }
}

@Composable
fun hiddenParamsFooter(hidden: List<SamplingParam>): String? {
    if (hidden.isEmpty()) return null
    val separator = stringResource(R.string.params_list_separator)
    val names = hidden.map { samplingParamTitle(it) }.joinToString(separator)
    return stringResource(R.string.params_hidden_footer, names)
}

@Composable
fun samplingParamTitle(param: SamplingParam): String = stringResource(
    when (param) {
        SamplingParam.TEMPERATURE -> R.string.params_temperature
        SamplingParam.TOP_P -> R.string.sheet_settings_top_p
        SamplingParam.TOP_K -> R.string.sheet_settings_top_k
        SamplingParam.MAX_TOKENS -> R.string.sheet_settings_max_tokens
        SamplingParam.FREQUENCY_PENALTY -> R.string.sheet_settings_frequency_penalty
        SamplingParam.PRESENCE_PENALTY -> R.string.sheet_settings_presence_penalty
        SamplingParam.REPETITION_PENALTY -> R.string.sheet_settings_repetition_penalty
    },
)

/** 各参数的取值范围与常用值；[NumericSettingSpec.defaultValue] 只是弹层的起始值，不代表模型默认。 */
fun samplingParamSpec(param: SamplingParam): NumericSettingSpec = when (param) {
    SamplingParam.TEMPERATURE -> continuous(0.0, 2.0, 1.0, listOf(0.2, 0.7, 1.0, 1.5))
    SamplingParam.TOP_P -> continuous(0.0, 1.0, 1.0, listOf(0.5, 0.8, 0.95, 1.0))
    SamplingParam.TOP_K -> NumericSettingSpec(1.0, 500.0, defaultValue = 40.0,
        presets = listOf(10.0, 20.0, 40.0, 100.0).map { NumericSettingSpec.Preset(it) })
    SamplingParam.MAX_TOKENS -> NumericSettingSpec(256.0, 262_144.0, defaultValue = 4096.0,
        presets = listOf(1024.0, 4096.0, 8192.0, 16_384.0, 32_768.0).map { NumericSettingSpec.Preset(it) })
    SamplingParam.REPETITION_PENALTY -> continuous(0.5, 2.0, 1.0, listOf(1.0, 1.05, 1.1, 1.2))
    SamplingParam.FREQUENCY_PENALTY, SamplingParam.PRESENCE_PENALTY -> continuous(-2.0, 2.0, 0.0, listOf(-1.0, 0.0, 0.5, 1.0))
}

private fun continuous(min: Double, max: Double, start: Double, presets: List<Double>) =
    NumericSettingSpec(min, max, step = 0.0, defaultValue = start,
        presets = presets.map { NumericSettingSpec.Preset(it) }, kind = NumericSettingKind.Continuous, decimalPlaces = 2)

/** 显示顺序：常用的温度、Top P 在前，惩罚项在后。 */
val SAMPLING_PARAM_ORDER: List<SamplingParam> = listOf(
    SamplingParam.TEMPERATURE,
    SamplingParam.TOP_P,
    SamplingParam.TOP_K,
    SamplingParam.MAX_TOKENS,
    SamplingParam.REPETITION_PENALTY,
    SamplingParam.PRESENCE_PENALTY,
    SamplingParam.FREQUENCY_PENALTY,
)
