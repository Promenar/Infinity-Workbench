package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.schedule.ScheduleRunResult
import com.promenar.infinity.domain.schedule.ScheduleTrigger
import com.promenar.infinity.domain.schedule.ScheduledTask
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val NOW = 1_792_400_000_000L

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

private val sampleJobs = listOf(
    BackgroundJob("a", "s1", BackgroundJobRunner.SHELL, "./gradlew assembleRelease", BackgroundJobState.RUNNING, null,
        NOW - 192_000, null, 120, BackgroundJobExitAction.WAKE, "jobs/a.log", "job:a"),
    BackgroundJob("b", "s2", BackgroundJobRunner.TERMUX, "pkg upgrade -y", BackgroundJobState.EXITED, 0,
        NOW - 900_000, NOW - 600_000, 120, BackgroundJobExitAction.NOTIFY, "jobs/b.log", "job:b"),
)

private fun sampleTask(id: String, session: String, name: String, trigger: ScheduleTrigger, exactRequested: Boolean, exactEffective: Boolean) = ScheduledTask(
    id = id, sessionId = session, name = name, prompt = "检查", trigger = trigger,
    exactRequested = exactRequested, exactEffective = exactEffective, enabled = true,
    nextFireAtMillis = NOW + 25 * 60_000, runCount = 3, maxRuns = null, expiresAtMillis = null,
    lastFiredAtMillis = NOW - 5 * 60_000, lastResult = ScheduleRunResult.COMPLETED, createdAtMillis = NOW - 86_400_000,
)

private val titles = mapOf("s1" to "发布构建", "s2" to "手机环境维护")

@PreviewTest
@Preview(name = "后台与定时设置 侧载 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun backgroundSettingsSideloadLightChinesePreview() = Frame(dark = false) {
    BackgroundActivitySettingsContent(
        conditions = BackgroundRunConditions(
            keepAliveAvailable = true, exactAlarmAvailable = true, exactAlarmGranted = false,
            batteryUnrestricted = false, notificationsAllowed = true,
        ),
        jobs = sampleJobs,
        schedules = listOf(
            sampleTask("x", "s1", "检查 CI 构建", ScheduleTrigger.Interval(30), exactRequested = true, exactEffective = false),
            sampleTask("y", "s2", "工作日早报", ScheduleTrigger.Cron("0 9 * * 1-5", "Asia/Shanghai"), exactRequested = false, exactEffective = false),
        ),
        sessionTitles = titles,
        nowMillis = NOW,
        actions = BackgroundActivitySettingsActions(),
    )
}

@PreviewTest
@Preview(name = "后台与定时设置 Play 深英360", widthDp = 360, heightDp = 760, locale = "en")
@Composable
fun backgroundSettingsPlayDarkEnglishPreview() = Frame(dark = true) {
    BackgroundActivitySettingsContent(
        conditions = BackgroundRunConditions(
            keepAliveAvailable = false, exactAlarmAvailable = false, exactAlarmGranted = false,
            batteryUnrestricted = true, notificationsAllowed = false,
        ),
        jobs = emptyList(),
        schedules = listOf(sampleTask("x", "s1", "Check CI build", ScheduleTrigger.Interval(60), exactRequested = false, exactEffective = false)),
        sessionTitles = mapOf("s1" to "Release build"),
        nowMillis = NOW,
        actions = BackgroundActivitySettingsActions(),
    )
}
