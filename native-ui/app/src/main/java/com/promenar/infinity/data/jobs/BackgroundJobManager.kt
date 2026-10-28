package com.promenar.infinity.data.jobs

import com.promenar.infinity.data.exec.*
import com.promenar.infinity.data.termux.*
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.trigger.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JobAccessException(val code: String, override val message: String) : IllegalArgumentException(message)
data class BackgroundJobLocation(val commandDirectory: File, val workspaceDirectory: File, val isolated: Boolean, val termuxShared: Boolean, val sessionId: String? = null, val workspaceKindOverride: String? = null) {
    val workspaceKind: String get() = workspaceKindOverride ?: if (isolated) "private" else if (termuxShared) "shared" else "project"
}
class PreparedBackgroundCommand(
    val request: BuiltinCommandRequest,
    val close: () -> Unit = {},
    val afterRun: suspend () -> Unit = {},
)
data class BackgroundJobStatus(val record: BackgroundJobRecord, val tail: JobLogTail, val timedOut: Boolean = false)

/** 唤醒的整个挂起区间由调用者的保活登记覆盖。 */
class BackgroundJobCompletion(
    private val dispatcher: () -> SystemTurnDispatcher? = { null },
    private val notify: suspend (BackgroundJobRecord) -> Unit = {},
) {
    suspend fun dispatch(record: BackgroundJobRecord, tail: JobLogTail) {
        when (BackgroundJobExitAction.fromWire(record.onExit)) {
            BackgroundJobExitAction.NOTIFY -> notify(record)
            BackgroundJobExitAction.WAKE -> {
                val target = dispatcher()
                if (target == null) notify(record) else {
                    try {
                        target.request(SystemTurnRequest(record.sessionId, SystemTurnKind.JOB_EXIT, record.id, record.summary, buildString {
                            appendLine("后台任务 ${record.id} 已结束")
                            appendLine("命令：${record.summary}")
                            appendLine("状态：${record.state}；退出码：${record.exitCode ?: "无"}；耗时：${elapsedSeconds(record)} 秒")
                            appendLine("日志通过 job_status 或 job_wait 读取。")
                            record.guidance?.let { appendLine(it) }
                            appendLine("日志末尾：\n${tail.text.takeLast(2_000)}")
                        }))
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { notify(record) }
                }
            }
            else -> Unit
        }
    }
    companion object {
        fun elapsedSeconds(record: BackgroundJobRecord, now: Long = System.currentTimeMillis()): Long =
            ((record.endedAtMillis ?: now) - record.startedAtMillis).coerceAtLeast(0) / 1000
    }
}

/** 应用级任务所有者；发起/等待工具的取消不会传递到已经接管的运行任务。 */
class BackgroundJobManager(
    private val scope: CoroutineScope,
    private val store: BackgroundJobStore,
    private val runRoot: File,
    private val runner: BuiltinCommandRunner,
    private val prepareWorkspace: suspend (BackgroundJobRecord) -> BackgroundJobLocation,
    private val prepareCommand: suspend (BackgroundJobRunner, String, String?, BackgroundJobLocation, Int) -> PreparedBackgroundCommand,
    private val reconcile: suspend (BackgroundJobRecord, Boolean, Boolean) -> String? = { _, _, _ -> null },
    private val termux: TermuxBackgroundJobs? = null,
    private val keepAlive: ExecKeepAlive = NoExecKeepAlive,
    private val liveOutput: ToolLiveOutputStore = ToolLiveOutputStore(),
    private val completion: BackgroundJobCompletion = BackgroundJobCompletion(),
    val keepAliveMode: String = "foreground_only",
    private val now: () -> Long = System::currentTimeMillis,
    private val executionGate: com.promenar.infinity.data.session.SessionExecutionGate? = null,
    private val sessionExists: suspend (String) -> Boolean = { true },
    private val linuxRuntime: com.promenar.infinity.domain.linux.LinuxRuntime? = null,
    private val sessionMode: suspend (String) -> com.promenar.infinity.domain.model.SessionRunMode? = { com.promenar.infinity.domain.model.SessionRunMode.BUILTIN },
    private val messages: RunModeMessages = RunModeMessages(),
) : BackgroundJobController {
    private class Active(val control: BuiltinCommandControl = BuiltinCommandControl()) {
        val terminal = CompletableDeferred<Unit>()
        var worker: Job? = null
        val inputGate = Mutex()
        val completion = CompletableDeferred<Pair<BackgroundJobRecord, JobLogTail>>()
        var sink: ToolLiveOutputSink = ToolLiveOutputSink.None
        var lastTail: String = ""
        @Volatile var suppressCompletion: Boolean = false
    }
    private val gate = Mutex()
    private val refreshGate = Mutex()
    private val publishGate = Mutex()
    private val pendingCleanup = mutableSetOf<String>()
    private val active = ConcurrentHashMap<String, Active>()
    private val deletedSessions = ConcurrentHashMap.newKeySet<String>()
    private val state = MutableStateFlow<List<BackgroundJob>>(emptyList())
    private val initializationMutex = Mutex()
    private var initialized = false

    suspend fun initialize() {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(subsystem = com.promenar.infinity.startup.StartupSubsystem.BACKGROUND_JOBS)
        initializationMutex.withLock {
            if (!initialized) withContext(Dispatchers.IO) { recover(); initialized = true }
        }
    }
    override fun observe(sessionId: String?): Flow<List<BackgroundJob>> = flow {
        initialize()
        emitAll(state.map { jobs -> jobs.filter { sessionId == null || it.sessionId == sessionId } })
    }
    private suspend fun publish() = publishGate.withLock {
        val records=store.all()
        state.value = records.map { it.job() }.sortedByDescending { it.startedAtMillis }
        // 只清理持久层明确淘汰的任务，不能用并发快照扫描“孤儿”。
        pendingCleanup += store.drainPruned().map{it.id}
        val eligible=pendingCleanup.filter{!active.containsKey(it)}
        eligible.forEach { id -> if(runCatching { removeRunDirectory(id) }.isSuccess) pendingCleanup.remove(id) }
    }
    private fun removeRunDirectory(id:String) {
        val directory=File(runRoot,id)
        if(id.matches(Regex("job-[A-Za-z0-9-]+")) && directory.canonicalFile.parentFile==runRoot.canonicalFile) {
            deleteJobTree(directory)
        }
    }
    private suspend fun recover() {
        store.prune()
        for (record in store.all()) {
            if (!record.running || record.runnerKind == BackgroundJobRunner.TERMUX) continue
            val cleaned=runner.interruptBackground(File(record.runDirectory,"pgid"))
            val reconcileWarning = try { reconcile(record, false, true) } catch (_: Exception) { "工作区恢复失败；任务副本和日志已保留。" }
            val warning=listOfNotNull(reconcileWarning,if(cleaned)null else "应用重启后无法核验旧进程组身份，未发送信号；请确认旧命令已停止。").joinToString(" ").ifEmpty{null}
            store.put(record.copy(state="INTERRUPTED",endedAtMillis=now(),guidance=warning,logMigrationDeferred=!cleaned))
        }
        for (record in store.all()) {
            try {
                if (record.logMigrationDeferred) continue
                if (record.runnerKind != BackgroundJobRunner.TERMUX) BackgroundJobLogs.migrate(record, runRoot)
                store.put(record.copy(logWorkspacePath = BackgroundJobLogs.reference(record.id)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                store.put(record.copy(guidance = listOfNotNull(record.guidance,
                    "日志迁移未完成（${failure::class.java.simpleName}），旧日志已保留。").distinct().joinToString(" ")))
            }
        }
        publish()
        for(record in store.all().filter { it.running && it.runnerKind == BackgroundJobRunner.TERMUX }) monitorTermux(record)
    }
    private suspend fun <T> admitted(sessionId:String, block:suspend()->T):T {
        suspend fun checked():T {
            if(!sessionExists(sessionId)) throw JobAccessException("JOB_SESSION_DELETED","会话不存在，不能启动后台任务。")
            return block()
        }
        return if(executionGate==null) checked() else executionGate.withNewSessionAdmission(sessionId) {checked()}
    }
    private suspend fun reserve(sessionId:String, root:String, kind:BackgroundJobRunner, source:String, minutes:Int, action:BackgroundJobExitAction):BackgroundJobRecord = gate.withLock {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = root, subsystem = com.promenar.infinity.startup.StartupSubsystem.BACKGROUND_JOBS)
        executionGate?.requireSessionWritable(sessionId)
        require(minutes in 1..1440) { "max_runtime_minutes 必须为 1–1440" }
        if(sessionId in deletedSessions) throw JobAccessException("JOB_SESSION_DELETED","会话正在删除，不能启动后台任务。")
        val running=store.all().filter { it.running }
        if(running.count{it.sessionId==sessionId} >= 3) throw JobAccessException("JOB_SESSION_LIMIT","本会话已有 3 个运行中任务，请先用 job_wait 等待或用 job_stop 停止。")
        if(running.size >= 6) throw JobAccessException("JOB_GLOBAL_LIMIT","全局已有 6 个运行中任务，请先结束部分后台任务。")
        val id="job-${UUID.randomUUID()}"
        BackgroundJobRecord(id,sessionId,root,kind.name,ExecRunInfo.summarize(source),startedAtMillis=now(),maxRuntimeMinutes=minutes,onExit=action.wireName,logWorkspacePath=BackgroundJobLogs.reference(id),runDirectory=File(runRoot,id).path).also {
            store.put(it); active[id]=Active(); publish()
        }
    }
    suspend fun startBuiltin(sessionId:String, workspaceRootUuid:String, kind:BackgroundJobRunner, source:String, stdin:String?, minutes:Int, action:BackgroundJobExitAction, expectedMode: com.promenar.infinity.domain.model.SessionRunMode? = null):BackgroundJobRecord = scope.async(Dispatchers.IO) { admitted(sessionId) {
        initialize()
        require(kind != BackgroundJobRunner.TERMUX)
        val mode = sessionMode(sessionId) ?: throw JobAccessException("EXEC_RUN_MODE_REQUIRED", messages(RunModeMessage.MODE_REQUIRED))
        if (expectedMode != null && mode != expectedMode) throw JobAccessException("EXEC_MODE_CHANGED", messages(RunModeMessage.MODE_CHANGED))
        val linux = mode == com.promenar.infinity.domain.model.SessionRunMode.LINUX
        if (linux && kind == BackgroundJobRunner.PYTHON || !linux && kind == BackgroundJobRunner.LINUX) {
            throw JobAccessException("EXEC_MODE_TOOL_UNAVAILABLE", messages(RunModeMessage.BUILTIN_TOOL_BLOCKED))
        }
        val actualKind = if (kind == BackgroundJobRunner.SHELL && linux) BackgroundJobRunner.LINUX else kind
        val record=reserve(sessionId,workspaceRootUuid,actualKind,source,minutes,action)
        val handle=active.getValue(record.id)
        val started=CompletableDeferred<BackgroundJobRecord>()
        handle.worker=scope.launch(Dispatchers.IO) {
            val sink=liveOutput.open("job:${record.id}")
            sink.onStop(handle.control::requestStop)
            try {
                keepAlive.hold(runInfo(record), handle.control::requestStop) {
                    var current=record
                    var command:PreparedBackgroundCommand?=null
                    try {
                        val location=prepareWorkspace(record)
                        current=record.copy(workspaceDirectory=location.workspaceDirectory.path,isolatedWorkspace=location.isolated)
                        store.put(current)
                        val log=logFile(current)
                        log.parentFile?.mkdirs(); if(!log.exists()) log.createNewFile()
                        reconcile(current,false,false)
                        val prepared=prepareCommand(actualKind,source,stdin,location.copy(sessionId=record.sessionId),minutes)
                        command=prepared
                        var linuxRemoved = false
                        val output = coroutineScope {
                            val watcher = if (actualKind == BackgroundJobRunner.LINUX) launch {
                                val runtime = checkNotNull(linuxRuntime)
                                runtime.state.first { it !is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready }
                                linuxRemoved = true
                                handle.control.requestStop()
                            } else null
                            try {
                                runner.runBackground(prepared.request,handle.control,log,File(record.runDirectory,"pgid"),onStarted={started.complete(current)},onOutput=sink::emit,onTerminalOutput={ sink.replace(ToolOutputStream.STDOUT, it) })
                            } finally {
                                withContext(NonCancellable) { watcher?.cancelAndJoin(); prepared.afterRun() }
                            }
                        }
                        val terminal=when {linuxRemoved -> "FAILED"; output.stoppedByUser -> "STOPPED"; output.timedOut -> "TIMED_OUT"; else -> "EXITED"}
                        finish(current.copy(state=terminal,exitCode=output.exitCode,endedAtMillis=now(),guidance=if(linuxRemoved) messages(RunModeMessage.LINUX_UNAVAILABLE) else if(actualKind == BackgroundJobRunner.LINUX) LinuxMissingCommandGuidance.hint(output.exitCode, readJobLogTail(log).text, messages) else null),handle)
                        if(!started.isCompleted) started.completeExceptionally(JobAccessException("JOB_STOPPED","任务在进程启动前被停止。"))
                    } catch (failure:Exception) {
                        val terminal=current.copy(state=if(failure is CancellationException) "INTERRUPTED" else "FAILED",endedAtMillis=now(),guidance=if(failure is com.promenar.infinity.domain.linux.LinuxUnavailableException) messages(RunModeMessage.LINUX_UNAVAILABLE) else "后台任务无法运行（${failure.javaClass.simpleName}），请检查执行后端和工作区。")
                        withContext(NonCancellable) {
                            if(store.all().find{it.id==record.id}?.running==true) finish(terminal,handle)
                        }
                        if(!started.isCompleted) started.completeExceptionally(failure)
                    } finally { command?.close?.invoke() }
                }
            } catch (failure:Exception) {
                if(!started.isCompleted) started.completeExceptionally(failure)
                withContext(NonCancellable) {
                    val latest=store.all().find{it.id==record.id}
                    if(latest?.running==true) finish(latest.copy(state="FAILED",endedAtMillis=now(),guidance="任务保活或运行初始化失败，请检查后台运行授权。"),handle)
                }
            } finally {
                handle.terminal.complete(Unit)
                active.remove(record.id,handle)
                liveOutput.close("job:${record.id}",sink)
            }
        }
        started.await()
    } }.await()

    /** 应用级协程从启动起拥有命令；让出仅扩大时限并把控制柄暴露给 job_*。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runLinuxForeground(
        sessionId: String, root: String, source: String, yieldSeconds: Int, minutes: Int,
        action: BackgroundJobExitAction, live: ToolLiveOutputSink,
        block: suspend (LinuxExecAttachment, ToolLiveOutputSink) -> com.promenar.infinity.domain.tool.CommandToolResult,
    ): com.promenar.infinity.domain.tool.CommandToolResult {
        val result = CompletableDeferred<com.promenar.infinity.domain.tool.CommandToolResult>()
        val yielded = java.util.concurrent.atomic.AtomicBoolean(false)
        var ownedHandle: Active? = null
        var ownedRecord: BackgroundJobRecord? = null
        var directory: File? = null
        try {
            withContext(NonCancellable) {
                initialize()
                val record = admitted(sessionId) { reserve(sessionId, root, BackgroundJobRunner.LINUX, source, minutes, action) }
                    .copy(directWorkspace = true)
                ownedRecord = record
                val handle = active.getValue(record.id)
                ownedHandle = handle
                handle.control.yieldAfterSeconds = yieldSeconds
                handle.control.yieldedTimeoutSeconds = minutes * 60
                store.put(record)
                val release = live.retain()
                val sink = liveOutput.open("job:${record.id}")
                sink.onStop(handle.control::requestStop)
                val tee = object : ToolLiveOutputSink by live {
                    override fun emit(stream: ToolOutputStream, text: String) { live.emit(stream, text); sink.emit(stream, text) }
                    override fun replace(stream: ToolOutputStream, text: String) { live.replace(stream, text); sink.replace(stream, text) }
                }
                handle.worker = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    var runtimeRemoved = false
                    try {
                        val value = coroutineScope {
                            val watcher = linuxRuntime?.let { runtime -> launch {
                                runtime.state.first { it !is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready }
                                runtimeRemoved = true; handle.control.requestStop()
                            } }
                            try { block(LinuxExecAttachment(handle.control, File(record.runDirectory, "pgid"), logFile(record)) { directory = it }, tee) }
                            finally { withContext(NonCancellable) { watcher?.cancelAndJoin() } }
                        }
                        withContext(NonCancellable) {
                            val terminal = record.copy(
                                workspaceDirectory = directory?.path,
                                state = when { runtimeRemoved -> "FAILED"; value.errorCode == "EXEC_STOPPED" -> "STOPPED"; value.timedOut -> "TIMED_OUT"; value.errorCode != null -> "FAILED"; else -> "EXITED" },
                                exitCode = value.exitCode, endedAtMillis = now(), commandResult = value.toJson(),
                                guidance = if (runtimeRemoved) messages(RunModeMessage.LINUX_UNAVAILABLE) else value.guidance,
                            )
                            store.put(terminal); publish(); handle.terminal.complete(Unit)
                            result.complete(value)
                            if (yielded.get() && sessionAllowed(record) && !handle.suppressCompletion) {
                                try { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) } catch (_: Exception) { }
                            }
                        }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            store.put(record.copy(workspaceDirectory = directory?.path, state = if (handle.control.stopRequested) "STOPPED" else if (failure is CancellationException) "INTERRUPTED" else "FAILED", endedAtMillis = now(), guidance = "命令执行失败（${failure.javaClass.simpleName}）。"))
                            publish(); result.completeExceptionally(failure)
                        }
                    } finally {
                        handle.terminal.complete(Unit); active.remove(record.id, handle)
                        release(); liveOutput.close("job:${record.id}", sink)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val record = checkNotNull(ownedRecord)
            val handle = checkNotNull(ownedHandle)
            // 环境准备与写锁等待不消耗前台运行阈值。
            kotlinx.coroutines.selects.select<Unit> { result.onAwait { }; handle.control.started.onAwait { } }
            val completed = if (yieldSeconds == 0) result.await() else kotlinx.coroutines.selects.select<com.promenar.infinity.domain.tool.CommandToolResult?> {
                result.onAwait { it }
                handle.control.yielded.onAwait { null }
            }
            currentCoroutineContext().ensureActive()
            if (completed != null) return completed
            // 无挂起区间先转移所有权，避免调用取消把已让出的进程停止。
            yielded.set(true)
            withContext(NonCancellable) { store.updateIfPresent(record.id) { it.copy(workspaceDirectory = directory?.path) } }
            if (result.isCompleted) return result.await()
            val output = handle.control.outputSnapshot
            val fields = kotlinx.serialization.json.buildJsonObject {
                put("runtime", kotlinx.serialization.json.JsonPrimitive("linux")); put("stderr_merged", kotlinx.serialization.json.JsonPrimitive(true))
                put("state", kotlinx.serialization.json.JsonPrimitive("running")); put("job_id", kotlinx.serialization.json.JsonPrimitive(record.id))
                put("yielded_after_seconds", kotlinx.serialization.json.JsonPrimitive(yieldSeconds))
                put("workspace_mode", kotlinx.serialization.json.JsonPrimitive("direct_reconcile_on_exit"))
            }
            return com.promenar.infinity.domain.tool.CommandToolResult(
                guidance = "命令仍在运行；用 job_status/job_wait 查看，用 job_input 回答提示，用 job_stop 结束。",
                stdout = output?.stdout.orEmpty().let { text -> directory?.let { BuiltinExecService.virtualize(text, it) } ?: text },
                stderr = output?.stderr.orEmpty(), stdoutOriginalLength = output?.stdoutOriginalLength ?: 0,
                stderrOriginalLength = output?.stderrOriginalLength ?: 0, stdoutTruncated = output?.stdoutTruncated ?: false,
                stderrTruncated = output?.stderrTruncated ?: false, backendFields = fields,
            )
        } catch (failure: Throwable) {
            if (!yielded.get()) withContext(NonCancellable) {
                ownedHandle?.let { handle ->
                    handle.control.requestStop()
                    if (handle.worker != null) handle.worker?.cancelAndJoin() else {
                        ownedRecord?.let { record ->
                            store.put(record.copy(state = "FAILED", endedAtMillis = now(), guidance = "任务启动未完成。"))
                            active.remove(record.id, handle); publish()
                        }
                        handle.terminal.complete(Unit)
                    }
                }
            }
            throw failure
        }
    }

    suspend fun input(sessionId: String, id: String, text: String, newline: Boolean = true, eof: Boolean = false): BackgroundJobStatus = withContext(Dispatchers.IO) {
        require(text.length <= 32_000 && '\u0000' !in text) { "text 应为不含 NUL 的字符串，最多32000字符。" }
        val record = owned(sessionId, id)
        if (!record.running) throw JobAccessException("JOB_NOT_RUNNING", "任务已经结束，不能写入输入。")
        val handle = active[id] ?: throw JobAccessException("JOB_INPUT_UNAVAILABLE", "任务输入不可用，请查询任务状态。")
        if (handle.control.input == null) throw JobAccessException("JOB_INPUT_UNAVAILABLE", "该任务没有交互输入；需要交互的 Linux shell 命令不要传 stdin。")
        handle.inputGate.withLock {
            val before = status(sessionId, id).tail.text
            try {
                withTimeout(5_000) { runInterruptible { handle.control.writeInput(text + if (newline) "\r" else "", eof) } }
            } catch (_: TimeoutCancellationException) {
                throw JobAccessException("JOB_INPUT_TIMEOUT", "输入写入超时，请用 job_status 查看状态后再决定是否重试。")
            } catch (failure: java.io.IOException) {
                if (store.all().any { it.id == id && !it.running } || handle.control.input == null) throw JobAccessException("JOB_NOT_RUNNING", "任务已经结束，不能写入输入。")
                throw JobAccessException("JOB_INPUT_FAILED", "输入写入失败，请用 job_status 查看状态。")
            }
            delay(1_000)
            val after = status(sessionId, id)
            after.copy(tail = after.tail.copy(text = if (after.tail.text.startsWith(before)) after.tail.text.drop(before.length) else after.tail.text))
        }
    }

    suspend fun startTermux(sessionId:String, workspaceRootUuid:String, source:String, workdir:String?, stdin:String?, minutes:Int, action:BackgroundJobExitAction):BackgroundJobRecord = scope.async(Dispatchers.IO) { admitted(sessionId) {
        initialize()
        TermuxCommandRequest(source,workdir,stdin=stdin)
        val backend=termux ?: throw JobAccessException("JOB_TERMUX_UNAVAILABLE","Termux 后台接入不可用，请检查工具设置。")
        val record=reserve(sessionId,workspaceRootUuid,BackgroundJobRunner.TERMUX,source,minutes,action)
        val handle=active.getValue(record.id)
        val started=CompletableDeferred<BackgroundJobRecord>()
        handle.worker=scope.launch(Dispatchers.IO) {
            val sink=liveOutput.open("job:${record.id}")
            handle.sink=sink
            sink.onStop {scope.launch{stop(record.id)}}
            var requested=false
            try {
                keepAlive.hold(runInfo(record), {scope.launch{stop(record.id)}}) {
                    try {
                        val location=prepareWorkspace(record)
                        val shared=location.workspaceDirectory.takeIf{location.termuxShared}
                        val routed=store.updateIfPresent(record.id) {latest -> if(latest.running) latest.copy(workspaceDirectory=shared?.path,logWorkspacePath=BackgroundJobLogs.reference(record.id)) else latest}
                        if(routed?.running==true) {
                            // 定位先落盘；启动回包丢失也能按任务号恢复控制。
                            requested=true
                            val remote=backend.start(record.id,source,workdir,stdin,minutes,shared)
                            store.updateIfPresent(record.id) { latest -> if(latest.running) latest.copy(termuxDirectory=remote.directory,termuxPid=remote.pid) else latest }
                        }
                    } catch(cancelled:CancellationException) {throw cancelled
                    } catch(failure:Exception) {
                        val unknown=requested && failure !is IllegalArgumentException
                        val saved=store.updateIfPresent(record.id) { latest -> if(!latest.running) latest else if(unknown) latest.copy(guidance="Termux 启动结果尚未确认；请用 job_status 查询，或用 job_stop 撤销该任务，避免重复执行。")
                            else latest.copy(state="FAILED",endedAtMillis=now(),guidance="Termux 参数或工作区准备失败，请检查工作目录与输入长度。") }
                        if(saved!=null && !saved.running) {handle.completion.complete(saved to JobLogTail("",false));handle.terminal.complete(Unit)}
                    }
                    publish()
                    started.complete(store.all().find{it.id==record.id} ?: record)
                    monitorTermuxLoop(record,handle)
                }
            } catch(failure:Exception) {
                if(!started.isCompleted) started.completeExceptionally(failure)
                withContext(NonCancellable) {
                    if(!requested) store.updateIfPresent(record.id) {latest -> if(latest.running) latest.copy(state="FAILED",endedAtMillis=now(),guidance="任务保活初始化失败，请检查后台运行授权。") else latest}
                    publish()
                }
            } finally {
                handle.terminal.complete(Unit)
                active.remove(record.id,handle)
                liveOutput.close("job:${record.id}",sink)
            }
        }
        started.await()
    } }.await()
    private fun monitorTermux(record:BackgroundJobRecord) {
        val handle=Active()
        if(active.putIfAbsent(record.id,handle)!=null) return
        handle.worker=scope.launch(Dispatchers.IO) {
            val sink=liveOutput.open("job:${record.id}")
            handle.sink=sink
            sink.onStop {scope.launch{stop(record.id)}}
            try {
                keepAlive.hold(runInfo(record),{scope.launch{stop(record.id)}}) { monitorTermuxLoop(record,handle) }
            } finally {handle.terminal.complete(Unit);active.remove(record.id,handle);liveOutput.close("job:${record.id}",sink)}
        }
    }
    private suspend fun monitorTermuxLoop(record:BackgroundJobRecord,handle:Active) {
        while(currentCoroutineContext().isActive) {
            if(!sessionAllowed(record)) {delay(100);continue}
            val current=store.all().find{it.id==record.id}
            if(current==null || !current.running) break
            try { refreshTermux(record) } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception) { /* 接入暂不可用时保留运行态，查询返回可操作错误。 */ }
            if(store.all().none{it.id==record.id && it.running}) break
            delay(2_000)
        }
        if(sessionAllowed(record) && !handle.suppressCompletion) {
            val (ended,tail)=handle.completion.await()
            completion.dispatch(ended,tail)
        }
    }
    private fun sessionAllowed(record:BackgroundJobRecord)=record.sessionId !in deletedSessions
    private fun termuxHandle(record:BackgroundJobRecord)=TermuxJobHandle(checkNotNull(record.termuxDirectory),checkNotNull(record.termuxPid))
    private suspend fun refreshTermux(record:BackgroundJobRecord):JobLogTail = refreshGate.withLock {
        var current=store.all().find{it.id==record.id} ?: return@withLock JobLogTail("",false)
        if(current.termuxPid==null) {
            val restored=checkNotNull(termux).recover(current.id,current.workspaceDirectory?.let(::File))
                ?: return@withLock JobLogTail("启动结果尚未确认；可用 job_stop 撤销，避免重复执行。",false)
            current=current.copy(termuxDirectory=restored.directory,termuxPid=restored.pid,guidance=null)
            store.put(current)
        }
        val backend = checkNotNull(termux)
        val result = try { backend.inspect(termuxHandle(current)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val recovered = backend.recover(current.id, current.workspaceDirectory?.let(::File)) ?: throw failure
            current = current.copy(termuxDirectory = recovered.directory, termuxPid = recovered.pid)
            store.put(current)
            backend.inspect(recovered)
        }
        if (!result.alive) {
            try {
                val migrated = backend.migrateCompleted(current.id, current.workspaceDirectory?.let(::File), termuxHandle(current))
                current = current.copy(termuxDirectory = migrated.directory, termuxPid = migrated.pid,
                    logWorkspacePath = BackgroundJobLogs.reference(current.id))
                store.put(current)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                current = current.copy(guidance = "Termux 日志迁移未完成（${failure::class.java.simpleName}），旧任务文件已保留。")
                store.put(current)
            }
        }
        active[record.id]?.let { running ->
            if (result.logTail != running.lastTail) {
                val text = if (result.logTail.startsWith(running.lastTail)) result.logTail.drop(running.lastTail.length) else result.logTail
                running.lastTail=result.logTail
                running.sink.emit(ToolOutputStream.STDOUT,text)
            }
        }
        val tail=JobLogTail(result.logTail.takeLast(8_000),result.truncated || result.logTail.length>8_000)
        if(current.running && !result.alive) {
            val next=current.copy(state=when {result.timedOut->"TIMED_OUT";result.stopped->"STOPPED";result.exitCode!=null->"EXITED";else->"FAILED"},exitCode=result.exitCode,endedAtMillis=now())
            // 终态落盘与所有权交接不可被一次查询的取消拆开。
            withContext(NonCancellable) {
                store.put(next)
                active[record.id]?.terminal?.complete(Unit)
                active[record.id]?.completion?.complete(next to tail)
                publish()
            }
        }
        tail
    }
    private suspend fun finish(record:BackgroundJobRecord,handle:Active) {
        var settled=record
        var reconciled=false
        val warning=try { (if (record.directWorkspace) null else reconcile(record,true,false)).also { reconciled=true }} catch(_:Exception){"工作区合入或日志发布失败，临时副本已保留；请检查工作区后处理任务产物。"}
        if(reconciled && record.isolatedWorkspace) {
            // 完成状态与日志仍可查询，完整工作区副本不再长期保留。
            val directory=File(runRoot,record.id)
            if(directory.canonicalFile.parentFile==runRoot.canonicalFile) deleteJobTree(File(directory,"workspace"))
        }
        if(warning!=null) settled=settled.copy(guidance=listOfNotNull(settled.guidance,warning).joinToString(" "))
        store.put(settled);publish();handle.terminal.complete(Unit)
        if(sessionAllowed(record)) {
            try { completion.dispatch(settled,readJobLogTail(logFile(settled))) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { /* 完成通知失败不能改写真实进程终态。 */ }
        }
    }
    private fun runInfo(record:BackgroundJobRecord)=ExecRunInfo(record.id,when(record.runnerKind) { BackgroundJobRunner.PYTHON -> ExecRunKind.PYTHON; BackgroundJobRunner.LINUX -> ExecRunKind.LINUX; else -> ExecRunKind.SHELL },record.summary,record.maxRuntimeMinutes*60,record.startedAtMillis,background=true)
    private fun logFile(record:BackgroundJobRecord):File =
        if (record.logMigrationDeferred) BackgroundJobLogs.legacyFile(record) ?: BackgroundJobLogs.file(record, runRoot)
        else BackgroundJobLogs.file(record, runRoot)
    private suspend fun owned(sessionId:String,id:String):BackgroundJobRecord {
        initialize()
        return store.all().find{it.id==id && it.sessionId==sessionId && sessionId !in deletedSessions}
            ?: throw JobAccessException("JOB_NOT_FOUND","本会话找不到该任务，请用 job_list 获取当前会话的任务号。")
    }
    override suspend fun readLog(sessionId: String, reference: String): com.promenar.infinity.domain.jobs.BackgroundJobLog {
        val id = BackgroundJobLogs.jobId(reference)
            ?: throw JobAccessException("JOB_NOT_FOUND", "任务日志引用无效。")
        val tail = status(sessionId, id).tail
        return com.promenar.infinity.domain.jobs.BackgroundJobLog(tail.text, tail.truncated)
    }
    suspend fun list(sessionId:String):List<BackgroundJobRecord> { initialize(); return store.all().filter{it.sessionId==sessionId && sessionId !in deletedSessions}.sortedByDescending{it.startedAtMillis} }
    suspend fun status(sessionId:String,id:String):BackgroundJobStatus = withContext(Dispatchers.IO) {
        var record=owned(sessionId,id)
        var warning: String? = null
        val tail=if(record.runnerKind==BackgroundJobRunner.TERMUX) refreshTermux(record) else {
            warning=if (record.directWorkspace) null else reconcile(record,false,false)
            val migration = if (record.logMigrationDeferred) null else runCatching { BackgroundJobLogs.migrate(record, runRoot) }.exceptionOrNull()
            if (migration != null) warning = listOfNotNull(warning, "日志迁移未完成（${migration::class.java.simpleName}），旧日志已保留。").joinToString(" ")
            val snapshot = active[id]?.control?.outputSnapshot
            val raw = snapshot?.let { JobLogTail(it.stdout.takeLast(8_000), it.stdoutTruncated || it.stdout.length > 8_000) } ?: readJobLogTail(logFile(record))
            val directory = record.workspaceDirectory?.let(::File)
            if (directory == null) raw else raw.copy(text = BuiltinExecService.virtualize(raw.text, directory))
        }
        record=owned(sessionId,id)
        BackgroundJobStatus(record.copy(guidance=listOfNotNull(record.guidance,warning).distinct().joinToString(" ").ifEmpty { null }),tail)
    }
    suspend fun waitFor(sessionId:String,id:String,timeoutSeconds:Int):BackgroundJobStatus {
        require(timeoutSeconds in 1..600){"timeout_seconds 必须为 1–600"}
        owned(sessionId,id)
        val ended=withTimeoutOrNull(timeoutSeconds*1_000L) {
            while(owned(sessionId,id).running) {
                delay(100)
            }
            true
        } ?: false
        return status(sessionId,id).copy(timedOut=!ended)
    }
    override suspend fun stop(jobId:String):Boolean = withContext(Dispatchers.IO) {
        initialize()
        val record=store.all().find{it.id==jobId && it.running} ?: return@withContext false
        val handle=active[jobId]
        if(record.runnerKind==BackgroundJobRunner.TERMUX) {
            refreshGate.withLock {
                val latest=store.all().find{it.id==jobId} ?: return@withLock
                if(!latest.running) return@withLock
                val result=if(latest.termuxPid==null) checkNotNull(termux).stopUnconfirmed(latest.id,latest.workspaceDirectory?.let(::File))
                    else checkNotNull(termux).stop(termuxHandle(latest))
                if(result?.alive==true) throw JobAccessException("JOB_STOP_UNCONFIRMED","Termux 进程组尚未确认停止，请检查 Termux 后重试 job_stop。")
                val next=latest.copy(state=when {result==null->"STOPPED";result.timedOut->"TIMED_OUT";result.stopped->"STOPPED";result.exitCode!=null->"EXITED";else->"FAILED"},exitCode=result?.exitCode,endedAtMillis=now())
                withContext(NonCancellable) {
                    store.put(next)
                    handle?.terminal?.complete(Unit)
                    handle?.completion?.complete(next to JobLogTail(result?.logTail.orEmpty(),result?.truncated ?: false))
                    publish()
                }
            }
        } else {
            handle?.control?.requestStop()
            if (record.directWorkspace && handle != null && !handle.control.started.isCompleted) handle.worker?.cancel()
        }
        handle?.terminal?.await()
        true
    }
    suspend fun stop(sessionId:String,id:String):BackgroundJobStatus { owned(sessionId,id);stop(id);return status(sessionId,id) }
    /** 删除事务准备阶段只停止任务，事务失败后会话仍可使用。 */
    suspend fun stopSession(sessionId:String) = withContext(NonCancellable + Dispatchers.IO) {
        initialize()
        gate.withLock {deletedSessions+=sessionId}
        try {
            val records=store.all().filter{it.sessionId==sessionId}
            records.forEach { active[it.id]?.suppressCompletion=true }
            for(record in records.filter{it.running}) stop(record.id)
            records.forEach {active[it.id]?.worker?.cancelAndJoin()}
        } finally {
            store.all().filter{it.sessionId==sessionId}.forEach{active[it.id]?.suppressCompletion=false}
            gate.withLock {deletedSessions-=sessionId}
        }
    }
    suspend fun deleteSession(sessionId:String) = withContext(NonCancellable + Dispatchers.IO) {
        initialize()
        gate.withLock {deletedSessions+=sessionId}
        var deleted=false
        try {
            val records=store.all().filter{it.sessionId==sessionId}
            records.forEach { active[it.id]?.suppressCompletion=true }
            for(record in records.filter{it.running}) stop(record.id)
            records.forEach {active[it.id]?.worker?.cancelAndJoin()}
            records.forEach {removeRunDirectory(it.id)}
            store.deleteSession(sessionId)
            deleted=true
            publish()
        } finally {
            if(!deleted) {
                store.all().filter{it.sessionId==sessionId}.forEach{active[it.id]?.suppressCompletion=false}
                gate.withLock{deletedSessions-=sessionId}
            }
        }
    }
}
