package com.promenar.nexara.background.generation

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 本类曾用于复现恢复期 Service 读取未初始化运行时的崩溃，现作为发布回归门禁。
 * 必须在 force-stop 后逐个执行测试方法，确保 Application 仍处于 Recovering。
 */
@RunWith(AndroidJUnit4::class)
class GenerationForegroundServiceColdStartDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun coldTrackDuringStartupRecoveryStopsGracefullyWithoutProcessCrash() {
        val app = context as com.promenar.nexara.NexaraApplication
        assertThat(app.startupState.value).isEqualTo(BackupStartupState.Recovering)
        val snapshot = GenerationTaskSnapshot(
            taskId = "cold-start-task",
            sessionId = "cold-start-session",
            assistantMessageId = "cold-start-assistant",
            phase = GenerationPhase.PREPARING,
            generatedChars = 0,
            startedAt = System.currentTimeMillis(),
        )

        ContextCompat.startForegroundService(
            context,
            GenerationForegroundService.trackIntent(context, snapshot),
        )

        assertThat(waitUntil(10_000) { !isServiceRunning() }).isTrue()
    }

    @Test
    fun coldStopDuringStartupRecoveryStopsGracefullyWithoutCoordinator() {
        val app = context as com.promenar.nexara.NexaraApplication
        assertThat(app.startupState.value).isEqualTo(BackupStartupState.Recovering)

        context.startService(
            Intent(context, GenerationForegroundService::class.java).apply {
                action = GenerationForegroundService.ACTION_STOP
                putExtra(GenerationForegroundService.EXTRA_TASK_ID, "cold-stop-task")
            },
        )

        assertThat(waitUntil(10_000) { !isServiceRunning() }).isTrue()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GenerationForegroundService::class.java.name }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (predicate()) return true
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return predicate()
    }
}
