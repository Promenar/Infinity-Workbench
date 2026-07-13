package com.promenar.nexara.background.generation

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationForegroundServiceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val app = context as NexaraApplication
    private val sessionId = "instrumentation-fgs-session"
    private val taskId = "instrumentation-fgs-task"
    private var runtimeReady = false

    @Before
    fun awaitApplicationRuntime() {
        runtimeReady = waitUntil(timeoutMillis = 20_000) {
            app.startupState.value == BackupStartupState.Ready
        }
        assertThat(runtimeReady).isTrue()
    }

    @After
    fun cleanup() {
        context.stopService(Intent(context, GenerationForegroundService::class.java))
        if (runtimeReady) {
            mutableSessionState().value = null
            app.generationCoordinator.release(sessionId, discardTerminal = true)
        }
        waitUntil(timeoutMillis = 5_000) { !isServiceRunning() }
    }

    @Test
    fun trackCommandStartsRealForegroundServiceAndStopActionEndsIt() {
        val snapshot = GenerationTaskSnapshot(
            taskId = taskId,
            sessionId = sessionId,
            assistantMessageId = "instrumentation-assistant",
            phase = GenerationPhase.STREAMING,
            generatedChars = 73,
            startedAt = System.currentTimeMillis(),
        )
        mutableSessionState().value = snapshot

        ContextCompat.startForegroundService(
            context,
            GenerationForegroundService.trackIntent(context, snapshot),
        )

        assertThat(waitUntil(timeoutMillis = 10_000) { isServiceRunning() }).isTrue()

        val notificationFactory = GenerationNotificationFactory(context, app.appIntentRouter)
        val notification = notificationFactory.create(snapshot)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.actions).hasLength(1)

        notification.actions.single().actionIntent.send()

        assertThat(waitUntil(timeoutMillis = 10_000) { !isServiceRunning() }).isTrue()
        assertThat(
            context.getSystemService(NotificationManager::class.java)
                .activeNotifications
                .none { it.id == GenerationNotificationFactory.NOTIFICATION_ID },
        ).isTrue()
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableSessionState(): MutableStateFlow<GenerationTaskSnapshot?> {
        val state = app.generationCoordinator.observe(sessionId)
        assertThat(state).isInstanceOf(MutableStateFlow::class.java)
        return state as MutableStateFlow<GenerationTaskSnapshot?>
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val serviceClassName = GenerationForegroundService::class.java.name
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClassName }
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate()) return true
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return predicate()
    }
}
