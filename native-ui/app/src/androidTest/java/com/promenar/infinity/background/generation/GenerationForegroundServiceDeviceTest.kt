package com.promenar.infinity.background.generation

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
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationForegroundServiceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val app = context as InfinityApplication
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
            publish(null)
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
        publish(snapshot)

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
        // 停止动作交给协调器取消任务，服务在协调器发布任务结束后才收尾；注入的快照没有真实任务，这里代为发布结束。
        publish(null)

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
    /** 同时写入按会话观察的快照与前台服务订阅的会话表，模拟协调器发布一轮生成。 */
    private fun publish(snapshot: GenerationTaskSnapshot?) {
        val state = app.generationCoordinator.observe(sessionId)
        assertThat(state).isInstanceOf(MutableStateFlow::class.java)
        (state as MutableStateFlow<GenerationTaskSnapshot?>).value = snapshot
        val field = app.generationCoordinator.javaClass.getDeclaredField("mutableSessions").apply { isAccessible = true }
        val sessions = field.get(app.generationCoordinator) as MutableStateFlow<Map<String, GenerationTaskSnapshot>>
        sessions.value = if (snapshot == null) sessions.value - sessionId else sessions.value + (sessionId to snapshot)
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
