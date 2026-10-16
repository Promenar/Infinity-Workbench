package com.promenar.nexara.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.data.skills.InstalledAgentSkill
import com.promenar.nexara.data.skills.SkillMarkdownParser
import com.promenar.nexara.ui.theme.forInput

/** 技能包列表与操作入口；无状态，便于截图与预览。 */
@Composable
fun AgentSkillsSection(
    skills: List<InstalledAgentSkill>,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Column {
        SectionCaption(title = null, description = stringResource(R.string.agent_skills_intro))
        BettboxListGroup {
            skills.forEachIndexed { index, skill ->
                AgentSkillItem(
                    skill = skill,
                    onToggle = { onToggle(skill.metadata.name, it) },
                    onOpen = { onOpen(skill.metadata.name) },
                    onDelete = { pendingDelete = skill.metadata.name },
                )
                if (index < skills.lastIndex) SkillRowDivider()
            }
            if (skills.isEmpty()) {
                SkillsEmptyState(
                    icon = Icons.Rounded.AutoAwesome,
                    message = stringResource(R.string.agent_skills_empty),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.agent_skills_import))
            }
            Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.agent_skills_create))
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.agent_skills_delete_title, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.shared_btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_btn_cancel)) }
            },
        )
    }
}

/** 技能包行：右侧只放开关，说明获得完整宽度；附带文件数与删除放在说明下方。点击行打开编辑。 */
@Composable
private fun AgentSkillItem(
    skill: InstalledAgentSkill,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SkillIconTile(Icons.Rounded.AutoAwesome)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.metadata.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    skill.metadata.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = skill.enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = SkillTextStart, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (skill.files.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.agent_skills_files_count, skill.files.size, skill.files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.shared_btn_delete))
            }
        }
    }
}

/** 新建或编辑技能；名称校验与 SKILL.md 规则一致。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSkillEditorSheet(
    initialName: String?,
    initialDescription: String,
    initialBody: String,
    allowedTools: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, body: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var description by remember { mutableStateOf(initialDescription) }
    var body by remember { mutableStateOf(initialBody) }
    val nameError = remember(name) {
        name.takeIf(String::isNotEmpty)?.let { runCatching { SkillMarkdownParser.validateName(it) }.exceptionOrNull() }
    }
    val canSave = name.isNotBlank() && nameError == null && description.isNotBlank() &&
        description.length <= SkillMarkdownParser.MAX_DESCRIPTION_LENGTH

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (initialName == null) R.string.agent_skills_create else R.string.agent_skills_edit),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = name,
                onValueChange = { name = it.trim().lowercase() },
                label = { Text(stringResource(R.string.agent_skills_name_hint)) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(stringResource(R.string.agent_skills_name_rule)) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = description,
                onValueChange = { description = it.take(SkillMarkdownParser.MAX_DESCRIPTION_LENGTH) },
                label = { Text(stringResource(R.string.agent_skills_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.agent_skills_body_hint)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace).forInput(),
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )
            if (allowedTools.isNotEmpty()) {
                Text(
                    stringResource(R.string.agent_skills_allowed_tools, allowedTools.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onSave(name, description, body) }, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shared_btn_save))
            }
        }
    }
}
