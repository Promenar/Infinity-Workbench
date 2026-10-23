package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WorkHistory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction
import com.promenar.nexara.domain.schedule.ScheduleCalculation
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.schedule.previewScheduleTriggers
import com.promenar.nexara.ui.chat.components.IntervalUnit
import com.promenar.nexara.ui.chat.components.fireTimeLabel
import com.promenar.nexara.ui.chat.components.intervalUnit
import com.promenar.nexara.ui.chat.components.triggerText
import com.promenar.nexara.ui.common.ChannelLimitChip
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 审批卡中 schedule_create 的结构化预览；与工具执行共用参数解析和渠道能力判断。 */
data class ScheduleApprovalPreview(
    val name: String,
    val prompt: String,
    val trigger: ScheduleTrigger,
    /** once 以 in_minutes 倒计时创建时的分钟数；审批卡按倒计时而非换算后的钟点描述触发方式。 */
    val countdownMinutes: Int? = null,
    val upcoming: List<Long>,
    /** 计算预览的时刻；“今天/明天”按它判断。 */
    val nowMillis: Long,
    val maxRuns: Int?,
    val exactRequested: Boolean,
    val exactEffective: Boolean,
    val exactAlarmAvailable: Boolean,
    /** 参数能解析但未通过校验：批准后工具会返回错误。 */
    val invalid: Boolean,
)

internal fun scheduleApprovalPreview(
    args: JsonObject,
    nowMillis: Long,
    zoneId: String,
    exactAlarmAvailable: Boolean,
    canScheduleExact: Boolean,
): ScheduleApprovalPreview? {
    val parsed = runCatching { ScheduleCalculation.parse(args, zoneId, nowMillis) }.getOrNull() ?: return null
    val upcoming = try {
        previewScheduleTriggers(args, nowMillis, zoneId, exactAlarmAvailable, canScheduleExact)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
    return ScheduleApprovalPreview(
        name = parsed.name,
        prompt = parsed.prompt,
        trigger = parsed.trigger,
        countdownMinutes = (args["trigger"] as? JsonObject)?.get("in_minutes")
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull }
            ?.takeIf { parsed.trigger is ScheduleTrigger.Once },
        upcoming = upcoming.orEmpty(),
        nowMillis = nowMillis,
        maxRuns = parsed.maxRuns,
        exactRequested = parsed.exactRequested,
        exactEffective = parsed.exactRequested && exactAlarmAvailable && canScheduleExact,
        exactAlarmAvailable = exactAlarmAvailable,
        invalid = upcoming == null,
    )
}

/** 命令类工具以 background:true 启动后台任务时的审批提示。 */
data class BackgroundRunPreview(
    val maxRuntimeMinutes: Int,
    val onExit: BackgroundJobExitAction,
    val keepAliveAvailable: Boolean,
)

internal fun backgroundRunPreview(args: JsonObject, keepAliveAvailable: Boolean): BackgroundRunPreview? {
    if ((args["background"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val minutes = (args["max_runtime_minutes"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?.coerceIn(1, 1_440) ?: 120
    val onExit = (args["on_exit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.let { wire -> BackgroundJobExitAction.entries.firstOrNull { it.wireName == wire } }
        ?: BackgroundJobExitAction.NOTIFY
    return BackgroundRunPreview(minutes, onExit, keepAliveAvailable)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleApprovalBlock(preview: ScheduleApprovalPreview) {
    Column(Modifier.testTag(UiTags.CHAT_APPROVAL_SCHEDULE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                text = preview.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when {
                preview.exactEffective -> ApprovalTag(stringResource(R.string.agent_schedules_exact))
                preview.exactRequested && !preview.exactAlarmAvailable -> ChannelLimitChip(
                    label = stringResource(R.string.agent_schedules_limit_label),
                    title = stringResource(R.string.agent_schedules_limit_title),
                    body = stringResource(R.string.agent_schedules_limit_body),
                    icon = Icons.Rounded.Schedule,
                )
                preview.exactRequested -> ChannelLimitChip(
                    label = stringResource(R.string.agent_schedules_limit_label),
                    title = stringResource(R.string.schedule_approval_exact_denied_title),
                    body = stringResource(R.string.schedule_approval_exact_denied_body),
                    icon = Icons.Rounded.Schedule,
                )
            }
        }
        Text(
            text = listOfNotNull(
                preview.countdownMinutes?.let { countdownText(it) } ?: triggerText(preview.trigger),
                preview.maxRuns?.let { pluralStringResource(R.plurals.schedule_approval_max_runs, it, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 提示是自然语言，按正文排版而非代码块。
        Text(
            text = preview.prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (preview.upcoming.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.schedule_approval_upcoming),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    preview.upcoming.forEach { ApprovalTag(fireTimeLabel(it, preview.nowMillis)) }
                }
            }
        }
        if (preview.invalid) ApprovalRiskLine(stringResource(R.string.schedule_approval_invalid))
    }
}

@Composable
private fun countdownText(minutes: Int): String = when (val unit = intervalUnit(minutes)) {
    is IntervalUnit.Minutes -> pluralStringResource(R.plurals.schedule_approval_in_minutes, unit.value, unit.value)
    is IntervalUnit.Hours -> pluralStringResource(R.plurals.schedule_approval_in_hours, unit.value, unit.value)
    is IntervalUnit.Days -> pluralStringResource(R.plurals.schedule_approval_in_days, unit.value, unit.value)
}

/** 命令正文下方的一行：后台运行、最长时长与结束后的动作；Play 版附带渠道限制说明。 */
@Composable
internal fun BackgroundRunLine(preview: BackgroundRunPreview) {
    Row(
        modifier = Modifier.testTag(UiTags.CHAT_APPROVAL_BACKGROUND),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.WorkHistory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        val minutes = preview.maxRuntimeMinutes
        val limit = if (minutes % 60 == 0) pluralStringResource(R.plurals.chat_approval_background_max_hours, minutes / 60, minutes / 60)
            else pluralStringResource(R.plurals.chat_approval_background_max_minutes, minutes, minutes)
        val exit = when (preview.onExit) {
            BackgroundJobExitAction.WAKE -> stringResource(R.string.chat_approval_background_on_exit_wake)
            BackgroundJobExitAction.NOTIFY -> stringResource(R.string.chat_approval_background_on_exit_notify)
            BackgroundJobExitAction.NONE -> null
        }
        Text(
            text = listOfNotNull(stringResource(R.string.chat_approval_background_run), limit, exit).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!preview.keepAliveAvailable) {
            ChannelLimitChip(
                label = stringResource(R.string.agent_jobs_limit_label),
                title = stringResource(R.string.agent_jobs_limit_title),
                body = stringResource(R.string.agent_jobs_limit_body),
                icon = Icons.Rounded.Visibility,
            )
        }
    }
}

@Composable
private fun ApprovalTag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
