package com.promenar.nexara.startup.diagnostics

import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 进程级入口只排队已经脱敏的事件，所有磁盘和导出 IO 都在 IO 调度器运行。 */
object AppDiagnostics {
    @Volatile private var session: RecorderSession? = null

    @Synchronized
    fun initialize(filesDir: File) {
        val directory = File(filesDir, "diagnostics").absoluteFile
        if (session?.directory == directory) return
        val previous = session
        session = RecorderSession(directory)
        previous?.commands?.close()
    }

    fun recordError(step: String, error: Throwable) {
        val active = session ?: return
        runCatching {
            active.commands.trySend(Command.Record(DiagnosticRedactor.snapshot(step, error)))
        }
    }

    fun recordStartupIssue(step: String, scopeKind: String, scopeId: String, error: Throwable) {
        val active = session ?: return
        runCatching {
            active.commands.trySend(Command.Record(DiagnosticRedactor.snapshot(step, error, scopeKind, scopeId)))
        }
    }

    suspend fun recent(): String {
        val active = session ?: return ""
        val result = CompletableDeferred<String>()
        active.commands.send(Command.Recent(result))
        return result.await()
    }

    suspend fun export(output: OutputStream, issueSummary: String) {
        val active = session
        if (active == null) {
            withContext(Dispatchers.IO) { writeDiagnosticExport(output, issueSummary, "") }
            return
        }
        val result = CompletableDeferred<Unit>()
        active.commands.send(Command.Export(output, issueSummary, result))
        result.await()
    }

    private sealed interface Command {
        data class Record(val event: DiagnosticEvent) : Command
        data class Recent(val result: CompletableDeferred<String>) : Command
        data class Export(val output: OutputStream, val issueSummary: String, val result: CompletableDeferred<Unit>) : Command
    }

    private class RecorderSession(val directory: File) {
        val commands = Channel<Command>(capacity = 128)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val recorder = AppDiagnosticRecorder(directory)

        init {
            scope.launch {
                try {
                    for (command in commands) {
                        when (command) {
                            is Command.Record -> runCatching { recorder.record(command.event) }
                            is Command.Recent -> runCatching { recorder.recent() }.fold(
                                onSuccess = command.result::complete,
                                onFailure = command.result::completeExceptionally,
                            )
                            is Command.Export -> runCatching { recorder.export(command.output, command.issueSummary) }.fold(
                                onSuccess = command.result::complete,
                                onFailure = command.result::completeExceptionally,
                            )
                        }
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
