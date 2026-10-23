package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.state.*
import com.promenar.nexara.ui.chat.progress
import com.promenar.nexara.ui.chat.stateValueText
import com.promenar.nexara.ui.common.rememberStreamAnimationsEnabled
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StateBoardPanel(
    snapshot: StateBoardSnapshot,
    isGenerating: Boolean,
    onEdit: suspend (Long, StateOperation) -> String?,
    onRemove: suspend (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val board = snapshot.board ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<StateBoardSnapshot, StateField>?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var removalError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    StateBoardBar(board, isGenerating, { expanded = true }, modifier)
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            StateBoardDetails(
                board = board,
                onEdit = { editing = snapshot to it },
                onRemove = { confirmingRemove = true },
                onClose = { expanded = false },
                isGenerating = isGenerating,
            )
            removalError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(NexaraSpacing.Medium)) }
        }
    }
    editing?.let { (original, field) ->
        StateFieldEditor(original, field, onDismiss = { editing = null }, onSave = onEdit)
    }
    if (confirmingRemove) {
        val revision = remember { snapshot.revision }
        AlertDialog(
            onDismissRequest = { if (!removing) confirmingRemove = false },
            title = { Text(stringResource(R.string.state_remove_title)) },
            text = { Text(stringResource(R.string.state_remove_body)) },
            confirmButton = {
                TextButton(enabled = !removing, onClick = {
                    removing = true
                    scope.launch {
                        try {
                            removalError = onRemove(revision)
                            confirmingRemove = false
                            if (removalError == null) expanded = false
                        } finally { removing = false }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_STATE_REMOVE_CONFIRM)) { Text(stringResource(R.string.state_remove)) }
            },
            dismissButton = { TextButton(enabled = !removing, onClick = { confirmingRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.state_cancel)) } },
        )
    }
}

@Composable
fun StateBoardBar(board: StateBoard, isGenerating: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.state_open), onClick = onClick)
            .padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.XSmall)
            .testTag(UiTags.CHAT_STATE_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Text(board.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        board.fields.take(2).forEach { field ->
            key(field.key) {
                StateFieldHighlight(field, isGenerating, Modifier.weight(1f)) {
                    Text("${field.label} ${stateValueText(field.value, field.unit)}", style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (field.type == "meter") LinearProgressIndicator(progress = { field.progress() }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun StateFieldHighlight(field: StateField, isGenerating: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val animations = rememberStreamAnimationsEnabled() == true
    var previous by remember(field.key) { mutableStateOf(field.value) }
    var highlight by remember(field.key) { mutableStateOf(false) }
    LaunchedEffect(field.value, animations) {
        val changed = previous != field.value
        previous = field.value
        highlight = changed && isGenerating && animations
        if (highlight) { delay(700); highlight = false }
    }
    val color by animateColorAsState(
        if (highlight) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = if (animations) tween(180) else snap(), label = "state_field_highlight",
    )
    Column(modifier.background(color, MaterialTheme.shapes.small).padding(2.dp), content = content)
}

@Composable
fun StateBoardDetails(board: StateBoard, onEdit: (StateField) -> Unit, onRemove: () -> Unit, onClose: () -> Unit = {}, isGenerating: Boolean = false) {
    Column(Modifier.fillMaxWidth().testTag(UiTags.CHAT_STATE_SHEET)) {
        Row(Modifier.fillMaxWidth().padding(start = NexaraSpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            Text(board.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.state_close)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            board.fields.forEach { field ->
                StateFieldHighlight(field, isGenerating, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.state_edit, field.label)) { onEdit(field) }
                    .padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.Small).testTag(UiTags.stateField(field.key))) {
                    Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (field.type == "list") {
                        val items = field.value.jsonArray
                        if (items.isEmpty()) Text(stringResource(R.string.state_board_empty_value), style = MaterialTheme.typography.bodyMedium)
                        items.forEach { Text("• ${it.jsonPrimitive.content}", style = MaterialTheme.typography.bodyMedium) }
                    } else Text(stateValueText(field.value, field.unit), style = MaterialTheme.typography.bodyMedium)
                    if (field.type == "meter") LinearProgressIndicator(progress = { field.progress() }, modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.XSmall))
                }
            }
        }
        TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.CHAT_STATE_REMOVE)) {
            Text(stringResource(R.string.state_remove), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun StateFieldEditor(snapshot: StateBoardSnapshot, field: StateField, onDismiss: () -> Unit, onSave: suspend (Long, StateOperation) -> String?) {
    var text by remember(field.key) { mutableStateOf(if (field.type == "meter") field.value.jsonObject.getValue("current").jsonPrimitive.content else stateValueText(field.value)) }
    var max by remember(field.key) { mutableStateOf(if (field.type == "meter") field.value.jsonObject.getValue("max").jsonPrimitive.content else "") }
    var items by remember(field.key) { mutableStateOf<List<String>>(if (field.type == "list") field.value.jsonArray.map { it.jsonPrimitive.content } else emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val invalidValue = stringResource(R.string.state_invalid_value)
    AlertDialog(
        modifier = Modifier.testTag(UiTags.CHAT_STATE_EDITOR),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.state_edit, field.label)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                if (field.type == "list") {
                    items.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value, { new -> items = items.toMutableList().also { it[index] = new.take(StateBoardEngine.MAX_ITEM_TEXT) } },
                                modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.state_item, index + 1)) }, enabled = !saving)
                            IconButton(enabled = !saving, onClick = { items = items.filterIndexed { i, _ -> i != index } }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.state_remove_item, index + 1))
                            }
                        }
                    }
                    TextButton(enabled = !saving && items.size < StateBoardEngine.MAX_ITEMS, onClick = { items = items + "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.state_add_item)) }
                } else {
                    OutlinedTextField(text, { text = it.take(StateBoardEngine.MAX_TEXT) }, modifier = Modifier.testTag(UiTags.CHAT_STATE_VALUE), enabled = !saving,
                        label = { Text(stringResource(if (field.type == "meter") R.string.state_current else R.string.state_value)) },
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "text") KeyboardType.Text else KeyboardType.Decimal),
                        singleLine = field.type != "text", minLines = if (field.type == "text") 3 else 1)
                    if (field.type == "meter") OutlinedTextField(max, { max = it.take(40) }, enabled = !saving,
                        modifier = Modifier.testTag(UiTags.CHAT_STATE_MAX), label = { Text(stringResource(R.string.state_max)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_STATE_SAVE), onClick = {
                val operation = try { com.promenar.nexara.ui.chat.stateFieldEdit(field, text, max, items) }
                    catch (_: IllegalArgumentException) { error = invalidValue; return@TextButton }
                saving = true
                scope.launch {
                    try {
                        error = onSave(snapshot.revision, operation)
                        if (error == null) onDismiss()
                    } finally { saving = false }
                }
            }) { Text(stringResource(R.string.state_save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.state_cancel)) } },
    )
}
