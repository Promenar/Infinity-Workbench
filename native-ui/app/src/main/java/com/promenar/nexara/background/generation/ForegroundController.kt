package com.promenar.nexara.background.generation

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

interface GenerationForegroundController {
    val failures: Flow<ForegroundServiceFailure>
    fun track(snapshot: GenerationTaskSnapshot): ForegroundStartResult
}

sealed interface ForegroundStartResult {
    data object StartedWithNotifications : ForegroundStartResult
    data object StartedWithoutNotifications : ForegroundStartResult
    data class NotAllowed(val cause: ForegroundServiceStartNotAllowedException) : ForegroundStartResult
    data class PermissionDenied(val cause: SecurityException) : ForegroundStartResult
}

enum class ForegroundServiceFailureOutcome { GENERATION_STOPPED }

data class ForegroundServiceFailure(
    val taskId: String,
    val outcome: ForegroundServiceFailureOutcome,
)

internal interface ForegroundServiceFailureReporter {
    fun reportFailure(taskId: String, outcome: ForegroundServiceFailureOutcome)
}

class AndroidGenerationForegroundController(
    context: Context,
) : GenerationForegroundController, ForegroundServiceFailureReporter {
    private val applicationContext = context.applicationContext
    private val mutableFailures = MutableSharedFlow<ForegroundServiceFailure>(extraBufferCapacity = 8)
    override val failures: Flow<ForegroundServiceFailure> = mutableFailures

    override fun track(snapshot: GenerationTaskSnapshot): ForegroundStartResult = try {
        ContextCompat.startForegroundService(
            applicationContext,
            GenerationForegroundService.trackIntent(applicationContext, snapshot),
        )
        if (
            Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            ForegroundStartResult.StartedWithNotifications
        } else {
            ForegroundStartResult.StartedWithoutNotifications
        }
    } catch (error: ForegroundServiceStartNotAllowedException) {
        ForegroundStartResult.NotAllowed(error)
    } catch (error: SecurityException) {
        ForegroundStartResult.PermissionDenied(error)
    }

    override fun reportFailure(taskId: String, outcome: ForegroundServiceFailureOutcome) {
        mutableFailures.tryEmit(ForegroundServiceFailure(taskId, outcome))
    }
}

object NoOpGenerationForegroundController : GenerationForegroundController {
    override val failures: Flow<ForegroundServiceFailure> = emptyFlow()
    override fun track(snapshot: GenerationTaskSnapshot) = ForegroundStartResult.StartedWithNotifications
}
