package com.promenar.infinity.ui.chat.command

import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityShapeTokens
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 候选列表最多约显示 5 行，其余滚动查看。 */
private val PaletteMaxHeight = 272.dp

/** 当前语言下的内置命令目录。 */
@Composable
fun rememberBuiltinSlashCommands(): List<SlashCommand> {
    val texts = BuiltinSlashCommand.entries.map { command ->
        val (name, description) = command.textRes()
        Triple(command, stringResource(name), stringResource(description))
    }
    return remember(texts) {
        texts.map { (command, name, description) ->
            SlashCommand(
                name = command.commandName,
                displayName = name,
                description = description,
                source = SlashCommandSource.BUILTIN,
                aliases = command.aliases,
                builtin = command,
            )
        }
    }
}

private fun BuiltinSlashCommand.textRes(): Pair<Int, Int> = when (this) {
    BuiltinSlashCommand.NEW -> R.string.slash_cmd_new_name to R.string.slash_cmd_new_desc
    BuiltinSlashCommand.RENAME -> R.string.slash_cmd_rename_name to R.string.slash_cmd_rename_desc
    BuiltinSlashCommand.PROMPT -> R.string.slash_cmd_prompt_name to R.string.slash_cmd_prompt_desc
    BuiltinSlashCommand.COMPACT -> R.string.slash_cmd_compact_name to R.string.slash_cmd_compact_desc
    BuiltinSlashCommand.CLEAR -> R.string.slash_cmd_clear_name to R.string.slash_cmd_clear_desc
    BuiltinSlashCommand.MODE -> R.string.slash_cmd_mode_name to R.string.slash_cmd_mode_desc
    BuiltinSlashCommand.TOOLS -> R.string.slash_cmd_tools_name to R.string.slash_cmd_tools_desc
    BuiltinSlashCommand.EXPORT -> R.string.slash_cmd_export_name to R.string.slash_cmd_export_desc
}

private fun SlashCommand.icon(): ImageVector = when (builtin) {
    null -> Icons.Rounded.AutoFixHigh
    BuiltinSlashCommand.NEW -> Icons.Rounded.AddComment
    BuiltinSlashCommand.RENAME -> Icons.Rounded.Edit
    BuiltinSlashCommand.PROMPT -> Icons.Rounded.Psychology
    BuiltinSlashCommand.COMPACT -> Icons.Rounded.Compress
    BuiltinSlashCommand.CLEAR -> Icons.Rounded.CleaningServices
    BuiltinSlashCommand.MODE -> Icons.Rounded.AdminPanelSettings
    BuiltinSlashCommand.TOOLS -> Icons.Rounded.Build
    BuiltinSlashCommand.EXPORT -> Icons.Rounded.Download
}

/**
 * 输入框浮岛中的命令候选：位于排队托盘与输入区之间，与托盘同一容器、同一行高与分隔线。
 * 内置命令用各自的功能图标，技能统一用技能图标并标注来源。键盘回车不会执行命令，只有点选或发送才会。
 */
@Composable
internal fun SlashCommandPalette(
    commands: List<SlashCommand>,
    onPick: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(UiTags.CHAT_SLASH_PALETTE)) {
        if (commands.isEmpty()) {
            Text(
                text = stringResource(R.string.slash_palette_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium)
                    .testTag(UiTags.CHAT_SLASH_EMPTY),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = PaletteMaxHeight)) {
                items(commands, key = { "${it.source}:${it.name}" }) { command ->
                    SlashCommandRow(command, onClick = { onPick(command) })
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = InfinitySpacing.Large),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun SlashCommandRow(command: SlashCommand, onClick: () -> Unit) {
    val sourceLabel = when (command.source) {
        SlashCommandSource.BUILTIN -> null
        SlashCommandSource.GLOBAL_SKILL -> stringResource(R.string.slash_source_global)
        SlashCommandSource.WORKSPACE_SKILL -> stringResource(R.string.slash_source_workspace)
    }
    val summary = listOfNotNull(command.displayName, sourceLabel, command.description.takeIf(String::isNotBlank)).joinToString(" · ")
    val description = stringResource(R.string.slash_item_cd, command.name, summary)
    val haptics = LocalInfinityHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button) {
                haptics.perform(InfinityHapticEvent.SELECTION)
                onClick()
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = 6.dp, bottom = 6.dp)
            .testTag(UiTags.CHAT_SLASH_ITEM_PREFIX + command.name),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = command.icon(),
            contentDescription = null,
            tint = if (command.isSkill) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(InfinitySpacing.Medium))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Text(
                    text = "/${command.name}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                command.displayName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                sourceLabel?.let { SourceTag(it) }
            }
            if (command.description.isNotBlank()) {
                Text(
                    text = command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourceTag(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, InfinityShapeTokens.XSmall)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
