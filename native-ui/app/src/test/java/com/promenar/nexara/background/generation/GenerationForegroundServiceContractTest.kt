package com.promenar.nexara.background.generation

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class GenerationForegroundServiceContractTest {
    private val manifest = read("app/src/main/AndroidManifest.xml")
    private val service = read(
        "app/src/main/java/com/promenar/nexara/background/generation/GenerationForegroundService.kt",
    )
    private val notificationFactory = read(
        "app/src/main/java/com/promenar/nexara/background/generation/GenerationNotificationFactory.kt",
    )

    @Test
    fun `Manifest声明通知与dataSync权限且Service不导出`() {
        assertThat(manifest).contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
        assertThat(manifest).contains("android.permission.POST_NOTIFICATIONS")
        val declaration = manifest.substringAfter(".background.generation.GenerationForegroundService")
            .substringBefore("/>")
        assertThat(declaration).contains("android:exported=\"false\"")
        assertThat(declaration).contains("android:foregroundServiceType=\"dataSync\"")
    }

    @Test
    fun `TRACK先进入前台再观察且Service不承诺进程死亡恢复`() {
        val track = functionBody("private fun handleTrack(intent: Intent)")
        assertThat(track.indexOf("startImmediately(initial)")).isAtLeast(0)
        assertThat(track.indexOf("activeCoordinator.observe(sessionId)"))
            .isGreaterThan(track.indexOf("startImmediately(initial)"))
        assertThat(service).contains("return START_NOT_STICKY")
        assertThat(service).contains("stopSelfResult(latestStartId)")
    }

    @Test
    fun `冷启动先用占位通知晋升且恢复期不构造Coordinator`() {
        val onCreate = functionBody("override fun onCreate()")
        assertThat(onCreate).doesNotContain("generationCoordinator")

        val onStart = functionBody(
            "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int",
        )
        val trackBranch = onStart.indexOf("ACTION_TRACK")
        val promotion = onStart.indexOf("promoteForCommand()")
        val dispatch = onStart.indexOf("handleTrack(intent)")
        assertThat(trackBranch).isAtLeast(0)
        assertThat(promotion).isGreaterThan(trackBranch)
        assertThat(dispatch).isGreaterThan(promotion)

        val track = functionBody("private fun handleTrack(intent: Intent)")
        val startupGuard = track.indexOf("app.startupState.value != BackupStartupState.Ready")
        val coordinatorAccess = track.indexOf("app.generationCoordinator")
        assertThat(startupGuard).isAtLeast(0)
        assertThat(coordinatorAccess).isGreaterThan(startupGuard)

        val stop = functionBody("private fun handleStop(intent: Intent)")
        assertThat(stop).contains("app.startupState.value != BackupStartupState.Ready")
        assertThat(stop).contains("stopUnconditionally()")
    }

    @Test
    fun `API35 timeout精确取消当前task后无条件停止`() {
        val timeout = functionBody("override fun onTimeout(startId: Int, fgsType: Int)")
        assertThat(timeout).contains("coordinator?.cancel(it, CancellationReason.TIMEOUT)")
        assertThat(timeout).contains("stopUnconditionally()")
        assertThat(functionBody("private fun stopUnconditionally()" )).contains("stopSelf()")
    }

    @Test
    fun `陈旧STOP在取消与停止之前先校验当前taskId`() {
        val stop = functionBody("private fun handleStop(intent: Intent)")
        val noTrackedTask = stop.indexOf(
            "if (trackedTaskId == null) {\n            stopSelfResult(latestStartId)\n            return\n        }",
        )
        val guard = stop.indexOf("if (!shouldHandleGenerationStop(trackedTaskId, taskId)) return")
        assertThat(noTrackedTask).isAtLeast(0)
        assertThat(guard).isGreaterThan(noTrackedTask)
        assertThat(guard).isAtLeast(0)
        assertThat(stop.indexOf("coordinator?.cancel")).isGreaterThan(guard)
        assertThat(stop.indexOf("stopTracking(taskId)")).isGreaterThan(guard)
    }

    @Test
    fun `通知停止动作使用一次性显式Service PendingIntent`() {
        val stopPendingIntent = notificationFactory
            .substringAfter("val stopPendingIntent =")
            .substringBefore("val openPendingIntent =")
        assertThat(stopPendingIntent).contains("PendingIntent.getService(")
        assertThat(stopPendingIntent).doesNotContain("PendingIntent.getBroadcast(")
        assertThat(stopPendingIntent).contains("PendingIntent.FLAG_IMMUTABLE")
        assertThat(stopPendingIntent).contains("PendingIntent.FLAG_ONE_SHOT")

        val stopIntent = notificationFactory
            .substringAfter("internal fun stopIntent(taskId: String)")
            .substringBefore("private fun ensureChannel()")
        assertThat(stopIntent).contains(
            "Intent(applicationContext, GenerationForegroundService::class.java)",
        )
        assertThat(stopIntent).contains("action = GenerationForegroundService.ACTION_STOP")
    }

    @Test
    fun `前台提升或通知创建失败精确上报取消并停止`() {
        val promotion = functionBody(
            "private fun startImmediately(snapshot: GenerationTaskSnapshot): Boolean",
        )
        assertThat(promotion).contains("ServiceCompat.startForeground")
        assertThat(service).contains("catch (_: RuntimeException)")
        assertThat(service).contains("handlePromotionFailure(snapshot.taskId)")

        val failure = functionBody("private fun handlePromotionFailure(taskId: String)")
        assertThat(failure).contains("if (trackedTaskId != taskId) return")
        assertThat(failure).contains("ForegroundServiceFailureOutcome.GENERATION_STOPPED")
        assertThat(failure).contains(
            "coordinator?.cancel(taskId, CancellationReason.BACKGROUND_UNAVAILABLE)",
        )
        assertThat(failure).contains("stopUnconditionally()")

        val update = functionBody("private fun updateNotification(snapshot: GenerationTaskSnapshot)")
        assertThat(update).contains("catch (_: SecurityException)")
        assertThat(update).contains("catch (_: RuntimeException)")
        assertThat(update).contains("handlePromotionFailure(snapshot.taskId)")
    }

    @Test
    fun `未知命令仅在没有跟踪任务时停止当前启动请求`() {
        val onStart = functionBody(
            "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int",
        )
        assertThat(onStart).contains(
            "else -> if (trackedTaskId == null) stopSelfResult(startId) else Unit",
        )

        val track = functionBody("private fun handleTrack(intent: Intent)")
        assertThat(track).contains("stopUnconditionally()")
        val stop = functionBody("private fun handleStop(intent: Intent)")
        assertThat(stop).contains("if (trackedTaskId == null) stopSelfResult(latestStartId)")
    }

    private fun read(path: String) = Files.readAllBytes(Path.of(path)).toString(Charsets.UTF_8)

    private fun functionBody(signature: String): String {
        val start = service.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = service.indexOf('{', start)
        var depth = 0
        for (index in brace until service.length) {
            when (service[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return service.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }
}
