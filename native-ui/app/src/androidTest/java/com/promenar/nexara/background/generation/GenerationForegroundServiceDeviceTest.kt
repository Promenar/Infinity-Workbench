package com.promenar.nexara.background.generation

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(GenerationNotificationFactory.NOTIFICATION_ID)
        val cleared = waitUntil(timeoutMillis = 5_000) {
            notificationManager.activeNotifications.none {
                it.id == GenerationNotificationFactory.NOTIFICATION_ID
            }
        }
        assertThat(cleared).isTrue()
    }

    @After
    fun cleanup() {
        context.stopService(Intent(context, GenerationForegroundService::class.java))
        if (runtimeReady) {
            mutableSessionState().value = null
            app.generationCoordinator.release(sessionId, discardTerminal = true)
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(GenerationNotificationFactory.NOTIFICATION_ID)
        waitUntil(timeoutMillis = 5_000) { !isServiceRunning() }
        val cleared = waitUntil(timeoutMillis = 5_000) {
            notificationManager.activeNotifications.none {
                it.id == GenerationNotificationFactory.NOTIFICATION_ID
            }
        }
        assertThat(cleared).isTrue()
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

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        assertThat(waitUntil(timeoutMillis = 10_000) {
            notificationManager.activeNotifications.any {
                it.id == GenerationNotificationFactory.NOTIFICATION_ID
            }
        }).isTrue()
        val notification = notificationManager.activeNotifications.single {
            it.id == GenerationNotificationFactory.NOTIFICATION_ID
        }.notification
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.actions).hasLength(1)

        val staleStopAction = notification.actions.single().actionIntent
        staleStopAction.send()

        assertThat(waitUntil(timeoutMillis = 10_000) { !isServiceRunning() }).isTrue()
        assertThat(waitUntil(timeoutMillis = 10_000) {
            notificationManager.activeNotifications
                .none { it.id == GenerationNotificationFactory.NOTIFICATION_ID }
        }).isTrue()

        val replayFailure = runCatching { staleStopAction.send() }.exceptionOrNull()
        assertThat(replayFailure).isInstanceOf(PendingIntent.CanceledException::class.java)

        assertThat(remainsQuiet(durationMillis = 1_500) {
            !isServiceRunning() && notificationManager.activeNotifications
                .none { it.id == GenerationNotificationFactory.NOTIFICATION_ID }
        }).isTrue()
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

    private fun remainsQuiet(durationMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + durationMillis
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (!predicate()) return false
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return predicate()
    }
}
