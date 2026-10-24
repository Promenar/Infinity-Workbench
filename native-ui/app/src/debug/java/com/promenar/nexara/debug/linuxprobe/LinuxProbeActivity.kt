package com.promenar.nexara.debug.linuxprobe

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** adb 可启动的 P0 工具，只有 sideloadDebug 注册和编译。 */
class LinuxProbeActivity : ComponentActivity() {
    private val model by viewModels<LinuxProbeViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            NexaraTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        Text(stringResource(R.string.linux_probe_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.linux_probe_location), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = model::start, enabled = !state.running, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (state.running) R.string.linux_probe_running else R.string.linux_probe_run))
                        }
                        state.result?.let { result ->
                            Text(stringResource(R.string.linux_probe_startup, result.startupSuccessfulSamples,
                                result.startupMedianMs?.toLong()?.toString() ?: "—", result.startupP90Ms?.toLong()?.toString() ?: "—"))
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        LazyColumn(Modifier.weight(1f)) {
                            itemsIndexed(state.result?.steps.orEmpty(), key = { index, _ -> index }) { _, step -> ProbeRow(step) }
                        }
                    }
                }
            }
        }
        if (intent.getBooleanExtra("auto_run", false)) model.autoStart()
    }
}

internal data class ProbeUiState(val running: Boolean = false, val result: LinuxProbeResult? = null, val error: String? = null)

internal class LinuxProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ProbeUiState())
    val state = mutableState.asStateFlow()
    private var started = false
    private var autoRunConsumed = false

    fun autoStart() {
        if (autoRunConsumed) return
        autoRunConsumed = true
        start()
    }

    fun start() {
        // auto_run 在屏幕旋转后不会触发第二次装包和基准测试。
        if (started) return
        started = true
        mutableState.value = ProbeUiState(running = true)
        viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    LinuxProbeRuntime(getApplication()).run { result -> mutableState.value = ProbeUiState(true, result) }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = error.toString())
            } finally {
                mutableState.value = mutableState.value.copy(running = false)
                started = false
            }
        }
    }
}

@Composable
private fun ProbeRow(step: ProbeStep) {
    var expanded by remember { mutableStateOf(false) }
    val status = when (step.status) {
        "running" -> R.string.linux_probe_status_running
        "passed" -> R.string.linux_probe_status_passed
        "timeout" -> R.string.linux_probe_status_timeout
        "skipped" -> R.string.linux_probe_status_skipped
        "cancelled" -> R.string.linux_probe_status_cancelled
        else -> R.string.linux_probe_status_failed
    }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp).padding(vertical = 8.dp)) {
        Text("${step.id} · ${step.runtime}", style = MaterialTheme.typography.titleSmall)
        Text("${stringResource(status)} · ${step.durationMs.toLong()} ms · exit=${step.exitCode ?: "—"}", style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            Text(step.command, style = MaterialTheme.typography.bodySmall)
            if (step.note.isNotEmpty()) Text(step.note, style = MaterialTheme.typography.bodySmall)
            Text("stdout (${step.stdoutBytes} B)\n${step.stdoutTail}", style = MaterialTheme.typography.bodySmall)
            Text("stderr (${step.stderrBytes} B)\n${step.stderrTail}", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
