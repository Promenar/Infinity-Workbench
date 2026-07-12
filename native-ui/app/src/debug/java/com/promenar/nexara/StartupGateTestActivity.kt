package com.promenar.nexara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.promenar.nexara.data.backup.BackupContent
import com.promenar.nexara.data.backup.BackupDataSource
import com.promenar.nexara.data.backup.BackupRuntime
import com.promenar.nexara.data.backup.BackupSnapshot
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.backup.ValidatedBackup
import com.promenar.nexara.ui.startup.StartupGate
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** 仅存在于 debug APK，用于 API 36 外部 adb UI tree 验证。 */
class StartupGateTestActivity : ComponentActivity() {
    private val state = MutableStateFlow<BackupStartupState>(BackupStartupState.Recovering)
    private val writerCalls = AtomicInteger()
    private var runtime: BackupRuntime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_BLOCKED -> state.value = BackupStartupState.Blocked
            MODE_READY -> state.value = BackupStartupState.Ready
            MODE_LONG -> {
                runtime = BackupRuntime(FakeDataSource {
                    withContext(NonCancellable) { delay(LONG_RECOVERY_MILLIS) }
                })
                launchRecovery()
            }
            MODE_FAILURE_RETRY -> {
                val attempts = AtomicInteger()
                val retryDelayMillis = intent.getLongExtra(
                    EXTRA_RETRY_DELAY_MILLIS,
                    DEFAULT_RETRY_RECOVERY_MILLIS,
                ).coerceIn(MIN_RETRY_RECOVERY_MILLIS, MAX_RETRY_RECOVERY_MILLIS)
                runtime = BackupRuntime(FakeDataSource {
                    if (attempts.incrementAndGet() == 1) error("debug injected recovery failure")
                    withContext(NonCancellable) { delay(retryDelayMillis) }
                })
                launchRecovery()
            }
            else -> state.value = BackupStartupState.Recovering
        }

        setContent {
            val startupState by state.collectAsStateWithLifecycle()
            NexaraTheme {
                StartupGate(startupState, onRetry = ::launchRecovery) {
                    Text(
                        text = "STARTUP_READY_MARKER_WRITERS_${writerCalls.get()}",
                        modifier = Modifier.testTag("startup-ready-content"),
                    )
                }
            }
        }
    }

    private fun launchRecovery() {
        val currentRuntime = runtime ?: run {
            state.value = BackupStartupState.Recovering
            return
        }
        state.value = BackupStartupState.Recovering
        lifecycleScope.launch {
            val result = currentRuntime.recoverBeforeWriters()
            if (result == BackupStartupState.Ready) writerCalls.incrementAndGet()
            state.value = result
        }
    }

    private class FakeDataSource(
        private val recover: suspend () -> Unit,
    ) : BackupDataSource {
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = recover()
    }

    companion object {
        const val EXTRA_MODE = "startup_mode"
        const val EXTRA_RETRY_DELAY_MILLIS = "retry_recovery_millis"
        const val MODE_BLOCKED = "blocked"
        const val MODE_READY = "ready"
        const val MODE_LONG = "long"
        const val MODE_FAILURE_RETRY = "failure_retry"
        private const val LONG_RECOVERY_MILLIS = 8_000L
        private const val DEFAULT_RETRY_RECOVERY_MILLIS = 5_000L
        private const val MIN_RETRY_RECOVERY_MILLIS = 2_000L
        private const val MAX_RETRY_RECOVERY_MILLIS = 10_000L
    }
}
