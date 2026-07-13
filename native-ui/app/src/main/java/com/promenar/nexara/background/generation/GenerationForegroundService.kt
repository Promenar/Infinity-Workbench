package com.promenar.nexara.background.generation

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.generation.CancellationReason
import com.promenar.nexara.domain.generation.GenerationCoordinator
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class GenerationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationFactory: GenerationNotificationFactory
    private lateinit var coordinator: GenerationCoordinator
    private var failureReporter: ForegroundServiceFailureReporter? = null
    private var observerJob: Job? = null
    private var trackedTaskId: String? = null
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        val app = application as NexaraApplication
        notificationFactory = GenerationNotificationFactory(this, app.appIntentRouter)
        coordinator = app.generationCoordinator
        failureReporter = app.generationForegroundController as? ForegroundServiceFailureReporter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_TRACK -> handleTrack(intent)
            ACTION_STOP -> handleStop(intent)
            else -> if (trackedTaskId == null) stopSelfResult(startId) else Unit
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        trackedTaskId?.let { coordinator.cancel(it, CancellationReason.TIMEOUT) }
        stopUnconditionally()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleTrack(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank)
        val assistantMessageId = intent.getStringExtra(EXTRA_ASSISTANT_MESSAGE_ID).orEmpty()
        if (taskId == null || sessionId == null) {
            if (trackedTaskId == null) stopSelfResult(latestStartId)
            return
        }
        val initial = GenerationTaskSnapshot(
            taskId = taskId,
            sessionId = sessionId,
            assistantMessageId = assistantMessageId,
            phase = GenerationPhase.PREPARING,
            generatedChars = 0,
            startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis()),
        )
        trackedTaskId = taskId
        if (!startImmediately(initial)) return
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            coordinator.observe(sessionId).collect { snapshot ->
                when (val decision = reduceGenerationForeground(taskId, snapshot)) {
                    is GenerationForegroundDecision.Update -> updateNotification(decision.snapshot)
                    is GenerationForegroundDecision.Stop -> stopTracking(decision.taskId)
                }
            }
        }
    }

    private fun handleStop(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank)
        if (taskId == null) {
            if (trackedTaskId == null) stopSelfResult(latestStartId)
            return
        }
        if (!shouldHandleGenerationStop(trackedTaskId, taskId)) return
        coordinator.cancel(taskId, CancellationReason.USER)
        stopTracking(taskId)
    }

    private fun startImmediately(snapshot: GenerationTaskSnapshot): Boolean = try {
        ServiceCompat.startForeground(
            this,
            GenerationNotificationFactory.NOTIFICATION_ID,
            notificationFactory.create(snapshot),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (_: RuntimeException) {
        handlePromotionFailure(snapshot.taskId)
        false
    }

    private fun updateNotification(snapshot: GenerationTaskSnapshot) {
        val notification = try {
            notificationFactory.create(snapshot)
        } catch (_: RuntimeException) {
            handlePromotionFailure(snapshot.taskId)
            return
        }
        try {
            getSystemService(NotificationManager::class.java).notify(
                GenerationNotificationFactory.NOTIFICATION_ID,
                notification,
            )
        } catch (_: SecurityException) {
            // Android 13+ 拒绝通知权限不影响已启动的前台生成任务。
        } catch (_: RuntimeException) {
            handlePromotionFailure(snapshot.taskId)
        }
    }

    private fun handlePromotionFailure(taskId: String) {
        if (trackedTaskId != taskId) return
        failureReporter?.reportFailure(
            taskId,
            ForegroundServiceFailureOutcome.GENERATION_STOPPED,
        )
        coordinator.cancel(taskId, CancellationReason.BACKGROUND_UNAVAILABLE)
        stopUnconditionally()
    }

    private fun stopTracking(expectedTaskId: String?) {
        if (expectedTaskId != null && trackedTaskId != expectedTaskId) return
        observerJob?.cancel()
        observerJob = null
        trackedTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    private fun stopUnconditionally() {
        observerJob?.cancel()
        observerJob = null
        trackedTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_TRACK = "com.promenar.nexara.generation.TRACK"
        const val ACTION_STOP = "com.promenar.nexara.generation.STOP"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_ASSISTANT_MESSAGE_ID = "assistant_message_id"
        const val EXTRA_STARTED_AT = "started_at"

        fun trackIntent(context: Context, snapshot: GenerationTaskSnapshot) =
            Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_TRACK
                putExtra(EXTRA_TASK_ID, snapshot.taskId)
                putExtra(EXTRA_SESSION_ID, snapshot.sessionId)
                putExtra(EXTRA_ASSISTANT_MESSAGE_ID, snapshot.assistantMessageId)
                putExtra(EXTRA_STARTED_AT, snapshot.startedAt)
            }
    }
}
