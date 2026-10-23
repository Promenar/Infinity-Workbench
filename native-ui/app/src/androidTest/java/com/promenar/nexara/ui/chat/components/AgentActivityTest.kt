package com.promenar.nexara.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.domain.jobs.BackgroundJob
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction
import com.promenar.nexara.domain.jobs.BackgroundJobRunner
import com.promenar.nexara.domain.jobs.BackgroundJobState
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.schedule.ScheduledTask
import com.promenar.nexara.domain.trigger.SystemTriggerMessage
import com.promenar.nexara.domain.trigger.SystemTurnKind
import com.promenar.nexara.ui.chat.SystemTriggerRow
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val now = 1_792_400_000_000L

    private fun job(id: String, state: BackgroundJobState) = BackgroundJob(
        id, "s", BackgroundJobRunner.SHELL, "sleep 90", state, if (state.isTerminal) 0 else null,
        now - 60_000, if (state.isTerminal) now - 1_000 else null, 120, BackgroundJobExitAction.WAKE, "jobs/$id.log", "job:$id",
    )

    @Test
    fun runningJobCanBeStoppedAndFinishedJobOpensLog() {
        val stopped = mutableListOf<String>()
        val opened = mutableListOf<String>()
        rule.setContent {
            NexaraTheme {
                BackgroundJobsContent(
                    jobs = listOf(job("run", BackgroundJobState.RUNNING), job("done", BackgroundJobState.EXITED)),
                    nowMillis = now,
                    keepAliveAvailable = false,
                    onStop = { stopped += it },
                    onOpenLog = { opened += it },
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHANNEL_LIMIT_CHIP).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.backgroundJobStop("run")).performClick()
        assertEquals(listOf("run"), stopped)

        rule.onNodeWithTag(UiTags.backgroundJob("done")).performClick()
        rule.onNodeWithText(resources.getString(R.string.agent_jobs_open_log)).performClick()
        assertEquals(listOf("jobs/done.log"), opened)
    }

    @Test
    fun scheduleToggleAndDeleteRequireExplicitActions() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val deleted = mutableListOf<String>()
        val task = ScheduledTask(
            id = "t", sessionId = "s", name = "检查 CI", prompt = "看构建", trigger = ScheduleTrigger.Interval(30),
            exactRequested = false, exactEffective = false, enabled = true, nextFireAtMillis = now + 600_000,
            runCount = 0, maxRuns = null, expiresAtMillis = null, lastFiredAtMillis = null, lastResult = null, createdAtMillis = now,
        )
        rule.setContent {
            NexaraTheme {
                ScheduledTasksContent(
                    tasks = listOf(task),
                    nowMillis = now,
                    exactAlarmAvailable = true,
                    onToggle = { id, enabled -> toggles += id to enabled },
                    onDelete = { deleted += it },
                    onGrantExact = null,
                )
            }
        }
        rule.onNodeWithTag(UiTags.scheduledTaskToggle("t")).performClick()
        assertEquals(listOf("t" to false), toggles)

        rule.onNodeWithTag(UiTags.scheduledTaskDelete("t")).performClick()
        assertEquals(emptyList<String>(), deleted)
        rule.onNodeWithText(resources.getString(R.string.agent_schedules_delete_confirm)).performClick()
        assertEquals(listOf("t"), deleted)
    }

    @Test
    fun systemTriggerBodyExpandsOnTap() {
        rule.setContent {
            NexaraTheme {
                SystemTriggerRow(
                    SystemTriggerMessage.Parsed(
                        kind = SystemTurnKind.SCHEDULE, sourceId = "t", title = "检查 CI", sequence = 2, catchUp = false,
                        firedAt = OffsetDateTime.now(), body = "查看最新一次构建",
                    ),
                )
            }
        }
        rule.onNodeWithTag(UiTags.SYSTEM_TRIGGER_BODY).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.SYSTEM_TRIGGER_ROW).performClick()
        rule.onNodeWithTag(UiTags.SYSTEM_TRIGGER_BODY).assertIsDisplayed()
    }
}
