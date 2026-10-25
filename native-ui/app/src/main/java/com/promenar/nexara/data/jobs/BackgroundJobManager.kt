package com.promenar.nexara.data.jobs

import com.promenar.nexara.data.exec.*
import com.promenar.nexara.data.termux.*
import com.promenar.nexara.domain.jobs.*
import com.promenar.nexara.domain.tool.ToolLiveOutputSink
import com.promenar.nexara.domain.tool.ToolOutputStream
import com.promenar.nexara.domain.trigger.*
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
                            appendLine("日志：${record.logWorkspacePath ?: record.termuxDirectory?.let { "$it.log（Termux 私有目录）" } ?: "不可用"}")
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
    private val executionGate: com.promenar.nexara.data.session.SessionExecutionGate? = null,
    private val sessionExists: suspend (String) -> Boolean = { true },
    private val linuxRuntime: com.promenar.nexara.domain.linux.LinuxRuntime? = null,
    private val sessionMode: suspend (String) -> com.promenar.nexara.domain.model.SessionRunMode? = { com.promenar.nexara.domain.model.SessionRunMode.BUILTIN },
    private val messages: RunModeMessages = RunModeMessages(),
) : BackgroundJobController {
    private class Active(val control: BuiltinCommandControl = BuiltinCommandControl()) {
        val terminal = CompletableDeferred<Unit>()
        var worker: Job? = null
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
    private val ready = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { recover() }

    suspend fun initialize() { ready.await() }
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
            store.put(record.copy(state="INTERRUPTED",endedAtMillis=now(),guidance=warning))
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
        executionGate?.requireSessionWritable(sessionId)
        require(minutes in 1..1440) { "max_runtime_minutes 必须为 1–1440" }
        if(sessionId in deletedSessions) throw JobAccessException("JOB_SESSION_DELETED","会话正在删除，不能启动后台任务。")
        val running=store.all().filter { it.running }
        if(running.count{it.sessionId==sessionId} >= 3) throw JobAccessException("JOB_SESSION_LIMIT","本会话已有 3 个运行中任务，请先用 job_wait 等待或用 job_stop 停止。")
        if(running.size >= 6) throw JobAccessException("JOB_GLOBAL_LIMIT","全局已有 6 个运行中任务，请先结束部分后台任务。")
        val id="job-${UUID.randomUUID()}"
        BackgroundJobRecord(id,sessionId,root,kind.name,ExecRunInfo.summarize(source),startedAtMillis=now(),maxRuntimeMinutes=minutes,onExit=action.wireName,logWorkspacePath="jobs/$id.log",runDirectory=File(runRoot,id).path).also {
            store.put(it); active[id]=Active(); publish()
        }
    }
    suspend fun startBuiltin(sessionId:String, workspaceRootUuid:String, kind:BackgroundJobRunner, source:String, stdin:String?, minutes:Int, action:BackgroundJobExitAction, expectedMode: com.promenar.nexara.domain.model.SessionRunMode? = null):BackgroundJobRecord = scope.async(Dispatchers.IO) { admitted(sessionId) {
        initialize()
        require(kind != BackgroundJobRunner.TERMUX)
        val mode = sessionMode(sessionId) ?: throw JobAccessException("EXEC_RUN_MODE_REQUIRED", messages(RunModeMessage.MODE_REQUIRED))
        if (expectedMode != null && mode != expectedMode) throw JobAccessException("EXEC_MODE_CHANGED", messages(RunModeMessage.MODE_CHANGED))
        val linux = mode == com.promenar.nexara.domain.model.SessionRunMode.LINUX
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
                                runtime.state.first { it !is com.promenar.nexara.domain.linux.LinuxRuntimeState.Ready }
                                linuxRemoved = true
                                handle.control.requestStop()
                            } else null
                            try {
                                runner.runBackground(prepared.request,handle.control,log,File(record.runDirectory,"pgid"),onStarted={started.complete(current)},onOutput=sink::emit)
                            } finally {
                                withContext(NonCancellable) { watcher?.cancelAndJoin(); prepared.afterRun() }
                            }
                        }
                        val terminal=when {linuxRemoved -> "FAILED"; output.stoppedByUser -> "STOPPED"; output.timedOut -> "TIMED_OUT"; else -> "EXITED"}
                        finish(current.copy(state=terminal,exitCode=output.exitCode,endedAtMillis=now(),guidance=if(linuxRemoved) messages(RunModeMessage.LINUX_UNAVAILABLE) else if(actualKind == BackgroundJobRunner.LINUX) LinuxMissingCommandGuidance.hint(output.exitCode, readJobLogTail(log).text, messages) else null),handle)
                        if(!started.isCompleted) started.completeExceptionally(JobAccessException("JOB_STOPPED","任务在进程启动前被停止。"))
                    } catch (failure:Exception) {
                        val terminal=current.copy(state=if(failure is CancellationException) "INTERRUPTED" else "FAILED",endedAtMillis=now(),guidance=if(failure is com.promenar.nexara.domain.linux.LinuxUnavailableException) messages(RunModeMessage.LINUX_UNAVAILABLE) else "后台任务无法运行（${failure.javaClass.simpleName}），请检查执行后端和工作区。")
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
                        val routed=store.updateIfPresent(record.id) {latest -> if(latest.running) latest.copy(workspaceDirectory=shared?.path,logWorkspacePath=record.logWorkspacePath.takeIf{shared!=null}) else latest}
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
        val result=checkNotNull(termux).inspect(termuxHandle(current))
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
        val warning=try {reconcile(record,true,false).also { reconciled=true }} catch(_:Exception){"工作区合入或日志发布失败，临时副本已保留；请检查工作区后处理任务产物。"}
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
    private fun logFile(record:BackgroundJobRecord):File = if(record.isolatedWorkspace) File(record.runDirectory,"output.log") else record.workspaceDirectory?.let { File(it,checkNotNull(record.logWorkspacePath)) } ?: File(record.runDirectory,"output.log")
    private suspend fun owned(sessionId:String,id:String):BackgroundJobRecord {
        initialize()
        return store.all().find{it.id==id && it.sessionId==sessionId && sessionId !in deletedSessions}
            ?: throw JobAccessException("JOB_NOT_FOUND","本会话找不到该任务，请用 job_list 获取当前会话的任务号。")
    }
    suspend fun list(sessionId:String):List<BackgroundJobRecord> { initialize(); return store.all().filter{it.sessionId==sessionId && sessionId !in deletedSessions}.sortedByDescending{it.startedAtMillis} }
    suspend fun status(sessionId:String,id:String):BackgroundJobStatus = withContext(Dispatchers.IO) {
        var record=owned(sessionId,id)
        var warning: String? = null
        val tail=if(record.runnerKind==BackgroundJobRunner.TERMUX) refreshTermux(record) else {
            warning=reconcile(record,false,false)
            readJobLogTail(logFile(record))
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
        } else handle?.control?.requestStop()
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
