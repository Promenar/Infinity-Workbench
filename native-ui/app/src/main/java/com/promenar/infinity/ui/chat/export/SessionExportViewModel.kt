package com.promenar.infinity.ui.chat.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportOptions
import com.promenar.infinity.domain.export.SessionExportResult
import com.promenar.infinity.domain.export.SessionExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 导出过程中需要界面配合的一次性事件。 */
sealed interface SessionExportEvent {
    /** 让用户选保存位置：单个文件用系统“另存为”，多个文件选一个文件夹。 */
    data class PickSaveTarget(val result: SessionExportResult) : SessionExportEvent
    data class Share(val result: SessionExportResult) : SessionExportEvent
    data class Saved(val displayName: String, val fileCount: Int) : SessionExportEvent
    data object ExportFailed : SessionExportEvent
    data object SaveFailed : SessionExportEvent
}

/** 把导出结果写到用户选的位置：单个文件写入 [target] 文档，多个文件写入 [target] 文件夹。 */
fun interface SessionExportTargetWriter {
    suspend fun write(result: SessionExportResult, target: Uri)
}

/**
 * 导出面板与 `/export <格式>` 共用的状态：格式与开关、预计输出、生成与保存。
 * 生成可取消；“保存到…”的结果在用户选好位置并写入后删除，分享出去的文件留给接收方读取，由导出服务按时清理。
 */
class SessionExportViewModel(
    private val sessionId: String,
    private val exporter: SessionExporter,
    private val writer: SessionExportTargetWriter,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionExportSheetState())
    val state: StateFlow<SessionExportSheetState> = _state.asStateFlow()

    private val _events = Channel<SessionExportEvent>(Channel.BUFFERED)
    val events: Flow<SessionExportEvent> = _events.receiveAsFlow()

    private var estimateJob: Job? = null
    private var exportJob: Job? = null

    /** 等待用户在系统选择器里选位置的导出结果。 */
    private var pendingSave: SessionExportResult? = null

    fun selectFormat(format: SessionExportFormat) {
        if (_state.value.running != null || _state.value.format == format) return
        _state.update { it.copy(format = format, estimate = SessionExportEstimateState.Loading) }
        refreshEstimate()
    }

    fun updateOptions(options: SessionExportOptions) {
        if (_state.value.running != null) return
        _state.update { it.copy(options = options) }
        refreshEstimate()
    }

    /** 重新估算；估算期间保留上一次的结果，避免切换开关时文字闪动。 */
    fun refreshEstimate() {
        val (format, options) = _state.value.let { it.format to it.options }
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch {
            val next = try {
                SessionExportEstimateState.Ready(exporter.estimate(sessionId, format, options))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SessionExportEstimateState.Unavailable
            }
            _state.update { if (it.format == format && it.options == options) it.copy(estimate = next) else it }
        }
    }

    fun save() = _state.value.let { start(SessionExportAction.SAVE, it.format, it.options) }

    fun share() = _state.value.let { start(SessionExportAction.SHARE, it.format, it.options) }

    /** `/export <格式>`：按默认开关直接导出并让用户选保存位置。 */
    fun exportDirectly(format: SessionExportFormat) = start(SessionExportAction.SAVE, format, SessionExportOptions())

    fun cancel() {
        exportJob?.cancel()
    }

    private fun start(action: SessionExportAction, format: SessionExportFormat, options: SessionExportOptions) {
        if (exportJob?.isActive == true) return
        _state.update { it.copy(running = action) }
        val job = viewModelScope.launch {
            val result = try {
                exporter.export(sessionId, format, options)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            _state.update { it.copy(running = null) }
            when {
                result == null -> _events.send(SessionExportEvent.ExportFailed)
                action == SessionExportAction.SHARE -> _events.send(SessionExportEvent.Share(result))
                else -> {
                    pendingSave?.let(exporter::release)
                    pendingSave = result
                    _events.send(SessionExportEvent.PickSaveTarget(result))
                }
            }
        }
        exportJob = job
        job.invokeOnCompletion { _state.update { if (exportJob === job) it.copy(running = null) else it } }
    }

    /** 系统选择器返回；[target] 为 null 表示用户取消，删除这次的临时文件。 */
    fun onSaveTarget(target: Uri?) {
        val result = pendingSave ?: return
        pendingSave = null
        if (target == null) {
            exporter.release(result)
            return
        }
        viewModelScope.launch {
            val saved = try {
                writer.write(result, target)
                true
            } catch (cancelled: CancellationException) {
                exporter.release(result)
                throw cancelled
            } catch (_: Exception) {
                false
            }
            exporter.release(result)
            _events.send(
                if (saved) SessionExportEvent.Saved(result.files.first().displayName, result.files.size)
                else SessionExportEvent.SaveFailed,
            )
        }
    }

    override fun onCleared() {
        pendingSave?.let(exporter::release)
        pendingSave = null
    }
}
