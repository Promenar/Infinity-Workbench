package com.promenar.nexara.background.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.domain.generation.CancellationReason
import com.promenar.nexara.domain.generation.GenerationCoordinator
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class GenerationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: NexaraApplication
    private lateinit var notificationFactory: GenerationNotificationFactory
    private var coordinator: GenerationCoordinator? = null
    private var failureReporter: ForegroundServiceFailureReporter? = null
    private var observerJob: Job? = null
    private var trackedTaskId: String? = null
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        app = application as NexaraApplication
        notificationFactory = GenerationNotificationFactory(this, app.appIntentRouter) { id -> app.chatStore.getSession(id)?.title }
        failureReporter = app.generationForegroundController as? ForegroundServiceFailureReporter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_TRACK -> if (promoteForCommand()) handleTrack(intent) else Unit
            ACTION_STOP -> handleStop(intent)
            else -> if (trackedTaskId == null) stopSelfResult(startId) else Unit
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator?.cancelForPolicy(GenerationRuntimePolicy.BACKGROUND_ALLOWED, CancellationReason.TIMEOUT)
        stopUnconditionally()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleTrack(intent: Intent) {
        if (app.startupState.value != BackupStartupState.Ready) {
            stopUnconditionally()
            return
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank)
        val assistantMessageId = intent.getStringExtra(EXTRA_ASSISTANT_MESSAGE_ID).orEmpty()
        if (taskId == null || sessionId == null) {
            stopUnconditionally()
            return
        }
        val activeCoordinator = app.generationCoordinator
        coordinator = activeCoordinator
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
            activeCoordinator.sessions.collect { snapshots ->
                when (val decision = reduceGenerationForeground(snapshots)) {
                    is GenerationForegroundDecision.Update -> updateNotification(listOf(decision.snapshot))
                    is GenerationForegroundDecision.UpdateMany -> updateNotification(decision.snapshots)
                    is GenerationForegroundDecision.Stop -> stopTracking(null)
                }
            }
        }
    }

    private fun handleStop(intent: Intent) {
        if (app.startupState.value != BackupStartupState.Ready) {
            stopUnconditionally()
            return
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank)
        if (intent.getBooleanExtra(EXTRA_STOP_ALL, false)) {
            val snapshots = coordinator?.sessions?.value.orEmpty().values.toList()
            coordinator?.cancelAll(CancellationReason.USER)
            if (snapshots.isEmpty()) stopTracking(null)
            return
        }
        if (taskId == null) {
            if (trackedTaskId == null) stopSelfResult(latestStartId)
            return
        }
        if (trackedTaskId == null) {
            stopSelfResult(latestStartId)
            return
        }
        if (coordinator?.sessions?.value?.values?.none { it.taskId == taskId } != false) return
        coordinator?.cancel(taskId, CancellationReason.USER)
    }

    private fun promoteForCommand(): Boolean = try {
        ensureStartingChannel()
        ServiceCompat.startForeground(
            this,
            GenerationNotificationFactory.NOTIFICATION_ID,
            startingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (_: RuntimeException) {
        stopUnconditionally()
        false
    }

    private fun ensureStartingChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(GenerationNotificationFactory.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                GenerationNotificationFactory.CHANNEL_ID,
                getString(R.string.generation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.generation_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun startingNotification(): Notification =
        NotificationCompat.Builder(this, GenerationNotificationFactory.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentText(getString(R.string.generation_notification_preparing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

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

    private fun updateNotification(snapshots: List<GenerationTaskSnapshot>) {
        val taskId = trackedTaskId ?: return
        val notification = try {
            notificationFactory.create(snapshots)
        } catch (_: RuntimeException) {
            handlePromotionFailure(taskId)
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
            handlePromotionFailure(taskId)
        }
    }

    private fun handlePromotionFailure(taskId: String) {
        if (trackedTaskId != taskId) return
        val affected = coordinator?.sessions?.value?.values.orEmpty()
            .filter { it.runtimePolicy == GenerationRuntimePolicy.BACKGROUND_ALLOWED }
        affected.forEach { snapshot ->
            failureReporter?.reportFailure(snapshot.taskId, ForegroundServiceFailureOutcome.GENERATION_STOPPED)
        }
        coordinator?.cancelForPolicy(GenerationRuntimePolicy.BACKGROUND_ALLOWED, CancellationReason.BACKGROUND_UNAVAILABLE)
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
        const val EXTRA_STOP_ALL = "stop_all"

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
