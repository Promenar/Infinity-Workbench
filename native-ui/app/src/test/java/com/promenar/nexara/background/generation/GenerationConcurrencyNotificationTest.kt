package com.promenar.nexara.background.generation

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.navigation.AppIntentRouter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenerationConcurrencyNotificationTest {
    @Test fun `单会话保持原有标题阶段和停止入口`() {
        val context = resourceContext()
        val factory = GenerationNotificationFactory(context, AppIntentRouter())
        val task = snapshot("one", GenerationPhase.STREAMING, 1)
        val notification = factory.create(listOf(task))
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(R.string.generation_notification_title))
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            .isEqualTo(context.getString(R.string.generation_notification_generating))
        assertThat(notification.actions.single().title.toString())
            .isEqualTo(context.getString(R.string.generation_notification_stop))
        assertThat(shadowOf(notification.actions.single().actionIntent).savedIntent
            .getStringExtra(GenerationForegroundService.EXTRA_TASK_ID)).isEqualTo(task.taskId)
    }

    @Test fun `多会话列出标题阶段并打开最近开始的会话`() {
        val context = resourceContext()
        val factory = GenerationNotificationFactory(context, AppIntentRouter()) { id -> "Title $id" }
        val first = snapshot("one", GenerationPhase.STREAMING, 1)
        val queued = snapshot("two", GenerationPhase.QUEUED, 0)
        val last = snapshot("three", GenerationPhase.THINKING, 2)
        val notification = factory.create(listOf(first, queued, last))
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.resources.getQuantityString(R.plurals.generation_concurrency_title, 3, 3))
        val lines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map(CharSequence::toString)
        assertThat(lines).hasSize(3)
        assertThat(lines[1]).contains("Title two")
        assertThat(lines[1]).contains(context.getString(R.string.generation_concurrency_queued))
        assertThat(notification.actions.single().title.toString()).isEqualTo(context.getString(R.string.generation_concurrency_stop_all))
        assertThat(shadowOf(notification.actions.single().actionIntent).savedIntent
            .getBooleanExtra(GenerationForegroundService.EXTRA_STOP_ALL, false)).isTrue()
        assertThat(shadowOf(notification.contentIntent).savedIntent
            .getStringExtra(AppIntentRouter.EXTRA_SESSION_ID)).isEqualTo("three")
    }

    private fun snapshot(session: String, phase: GenerationPhase, order: Long) = GenerationTaskSnapshot(
        taskId = "task-$session", sessionId = session, assistantMessageId = "assistant", phase = phase,
        generatedChars = 0, startedAt = order, startedOrder = order,
    )

    private fun resourceContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Application>()
        return object : ContextWrapper(base) {
            private val fixture = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
                override fun getString(id: Int): String = when (id) {
                    R.string.generation_notification_channel -> "Generation"
                    R.string.generation_notification_channel_description -> "Generation status"
                    R.string.generation_notification_title -> "Generating"
                    R.string.generation_notification_preparing -> "Preparing"
                    R.string.generation_notification_thinking -> "Thinking"
                    R.string.generation_notification_executing_tools -> "Executing tools"
                    R.string.generation_notification_generating -> "Generating reply"
                    R.string.generation_notification_finishing -> "Finishing"
                    R.string.generation_notification_stop -> "Stop"
                    R.string.p0_progress -> "%1\$d rounds, %2\$d calls, %3\$d tokens %4\$s"
                    R.string.p0_estimated -> "estimated"
                    R.string.generation_concurrency_queued -> "Queued"
                    R.string.generation_concurrency_untitled -> "Untitled"
                    R.string.generation_concurrency_line -> "%1\$s: %2\$s"
                    R.string.generation_concurrency_details -> "Show sessions"
                    R.string.generation_concurrency_stop_all -> "Stop all"
                    else -> super.getString(id)
                }
                override fun getString(id: Int, vararg formatArgs: Any): String =
                    String.format(Locale.US, getString(id), *formatArgs)
                override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any): String =
                    if (id == R.plurals.generation_concurrency_title) "${formatArgs[0]} sessions generating"
                    else super.getQuantityString(id, quantity, *formatArgs)
            }
            override fun getResources(): Resources = fixture
            override fun getApplicationContext(): Context = this
        }
    }
}
