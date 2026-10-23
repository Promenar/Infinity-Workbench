package com.promenar.nexara.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.exec.*
import com.promenar.nexara.domain.jobs.*
import com.promenar.nexara.domain.trigger.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Test

class BackgroundJobManagerTest {
    @Test fun `后台立即返回且取消发起轮次不影响任务`() = fixture { m ->
        val started=CompletableDeferred<BackgroundJobRecord>()
        val caller=launch { started.complete(m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"while [ ! -f finish ]; do sleep 0.05; done; echo done",null,1,BackgroundJobExitAction.NONE)); awaitCancellation() }
        val job=withTimeout(5000){started.await()}
        assertThat(m.status("s",job.id).record.running).isTrue(); caller.cancelAndJoin()
        File(job.workspaceDirectory!!,"finish").createNewFile()
        val result=m.waitFor("s",job.id,5)
        assertThat(result.timedOut).isFalse()
        assertThat(result.record.state).isEqualTo("EXITED")
        assertThat(result.tail.text).contains("done")
    }
    @Test fun `会话隔离与等待超时取消不停止任务`() = fixture { m ->
        val job=m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"sleep 60",null,1,BackgroundJobExitAction.NONE)
        assertThat(runCatching { m.status("other",job.id) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
        assertThat(m.waitFor("s",job.id,1).timedOut).isTrue()
        val waiter=launch { m.waitFor("s",job.id,600) }; delay(50); waiter.cancelAndJoin()
        assertThat(m.status("s",job.id).record.running).isTrue()
        assertThat(m.stop(job.id)).isTrue()
        assertThat(m.status("s",job.id).record.state).isEqualTo("STOPPED")
    }
    @Test fun `每会话三项全局六项并发上限与删除停止`() = fixture { m ->
        repeat(3) { m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"sleep 60",null,1,BackgroundJobExitAction.NONE) }
        assertThat(runCatching { m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"true",null,1,BackgroundJobExitAction.NONE) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
        repeat(3) { m.startBuiltin("t","root",BackgroundJobRunner.SHELL,"sleep 60",null,1,BackgroundJobExitAction.NONE) }
        assertThat(runCatching { m.startBuiltin("u","root",BackgroundJobRunner.SHELL,"true",null,1,BackgroundJobExitAction.NONE) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
        m.deleteSession("s"); m.deleteSession("t")
        assertThat(m.list("s")).isEmpty()
    }
    @Test fun `结束分发notify与wake且调度器缺失退化`() = runBlocking {
        var notices=0
        val requests=mutableListOf<SystemTurnRequest>()
        val dispatcher=object:SystemTurnDispatcher { override suspend fun request(request:SystemTurnRequest):SystemTurnOutcome {requests+=request; return SystemTurnOutcome.Completed} }
        val completion=BackgroundJobCompletion({dispatcher}) { notices++ }
        val record=sample().copy(state="EXITED",exitCode=0,endedAtMillis=10)
        completion.dispatch(record.copy(onExit="none"),JobLogTail("tail",false))
        completion.dispatch(record.copy(onExit="notify"),JobLogTail("tail",false))
        completion.dispatch(record.copy(onExit="wake"),JobLogTail("tail",false))
        assertThat(notices).isEqualTo(1)
        assertThat(requests.single().body).contains("tail")
        BackgroundJobCompletion({null}){notices++}.dispatch(record.copy(onExit="wake"),JobLogTail("",false))
        assertThat(notices).isEqualTo(2)
    }
    @Test fun `重启内置记录标记中断`() = runBlocking {
        val root=Files.createTempDirectory("job-recover").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val store=BackgroundJobStore(File(root,"store"))
            store.put(sample().copy(runDirectory=File(root,"run").path))
            val m=manager(root,scope,store); m.initialize()
            assertThat(m.list("s").single().state).isEqualTo("INTERRUPTED")
        } finally {scope.cancel(); root.deleteRecursively()}
    }
    @Test fun `唤醒调度器返回前保持登记`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-wake").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val held=java.util.concurrent.atomic.AtomicInteger()
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T> {held.incrementAndGet();return try{ExecHeld(block(),ExecForegroundState.STARTED)}finally{held.decrementAndGet()}}}
        val dispatcher=object:SystemTurnDispatcher {override suspend fun request(request:SystemTurnRequest):SystemTurnOutcome {entered.complete(Unit);release.await();return SystemTurnOutcome.Completed}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),completion=BackgroundJobCompletion({dispatcher}),keepAlive=keep)
        try {
            val job=m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"echo done",null,1,BackgroundJobExitAction.WAKE)
            withTimeout(5000){entered.await()}
            assertThat(m.status("s",job.id).record.state).isEqualTo("EXITED")
            assertThat(held.get()).isEqualTo(1)
            release.complete(Unit)
            withTimeout(5000){while(held.get()>0)delay(10)}
        } finally{release.complete(Unit);scope.cancel();root.deleteRecursively()}
    }
    @Test fun `状态查询的冲突警告不会复活已经结束的任务`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-status-race").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val intercept=java.util.concurrent.atomic.AtomicBoolean(false)
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),reconcile={_,finished,_->if(!finished && intercept.compareAndSet(true,false)){entered.complete(Unit);release.await();"日志冲突"}else null})
        try {
            val job=m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"while [ ! -f go ]; do sleep 0.05; done",null,1,BackgroundJobExitAction.NONE)
            intercept.set(true)
            val query=async{m.status("s",job.id)}
            withTimeout(5000){entered.await()}
            File(root,"go").createNewFile()
            withTimeout(5000){while(m.list("s").single().running)delay(10)}
            release.complete(Unit)
            assertThat(query.await().record.state).isEqualTo("EXITED")
            assertThat(m.list("s").single().state).isEqualTo("EXITED")
        } finally{release.complete(Unit);scope.cancel();root.deleteRecursively()}
    }
    @Test fun `Termux启动回包晚于状态终态时仍唤醒一次并保活`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-termux-race").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val launching=CompletableDeferred<Unit>();val reply=CompletableDeferred<Unit>()
        val waking=CompletableDeferred<Unit>();val finishWake=CompletableDeferred<Unit>()
        val count=java.util.concurrent.atomic.AtomicInteger();val held=java.util.concurrent.atomic.AtomicInteger()
        val encoded=java.util.Base64.getEncoder().encodeToString("/termux/jobs/job-test".toByteArray())
        val handle="NEXARA_JOB_V1\n$encoded\n12345\n"
        val backend=com.promenar.nexara.data.termux.TermuxBackgroundJobs { request ->
            val output=when {
                "launch=" in request.command -> {launching.complete(Unit);reply.await();handle}
                "code=-" in request.command -> "NEXARA_JOB_V1\n0\n0\n0\n0\n4\ndGFpbA==\n"
                else -> handle
            }
            com.promenar.nexara.data.termux.TermuxCommandResult(stdout=output,exitCode=0)
        }
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T>{held.incrementAndGet();return try{ExecHeld(block(),ExecForegroundState.STARTED)}finally{held.decrementAndGet()}}}
        val dispatcher=object:SystemTurnDispatcher {override suspend fun request(request:SystemTurnRequest):SystemTurnOutcome{count.incrementAndGet();waking.complete(Unit);finishWake.await();return SystemTurnOutcome.Completed}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),completion=BackgroundJobCompletion({dispatcher}),keepAlive=keep,termux=backend)
        try {
            val caller=async {m.startTermux("s","root","true",null,null,1,BackgroundJobExitAction.WAKE)}
            withTimeout(5000){launching.await()}
            val id=m.list("s").single().id
            assertThat(m.status("s",id).record.state).isEqualTo("EXITED")
            assertThat(held.get()).isEqualTo(1)
            reply.complete(Unit);withTimeout(5000){caller.await();waking.await()}
            assertThat(count.get()).isEqualTo(1);assertThat(held.get()).isEqualTo(1)
            finishWake.complete(Unit)
            withTimeout(5000){while(held.get()>0)delay(10)}
        } finally{reply.complete(Unit);finishWake.complete(Unit);scope.cancel();deleteJobTree(root)}
    }
    @Test fun `保活初始化失败不会让启动永久挂起`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-hold-failed").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T>{error("unavailable")}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),keepAlive=keep,termux=com.promenar.nexara.data.termux.TermuxBackgroundJobs{error("不应启动远端命令")})
        try {
            val error=withTimeout(5000){runCatching {m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"true",null,1,BackgroundJobExitAction.NONE)}.exceptionOrNull()}
            assertThat(error).isInstanceOf(IllegalStateException::class.java)
            withTimeout(5000){while(m.list("s").single().running)delay(10)}
            val termuxError=withTimeout(5000){runCatching {m.startTermux("t","root","true",null,null,1,BackgroundJobExitAction.NONE)}.exceptionOrNull()}
            assertThat(termuxError).isInstanceOf(IllegalStateException::class.java)
            withTimeout(5000){while(m.list("t").single().running)delay(10)}
            assertThat(m.list("t").single().state).isEqualTo("FAILED")
        } finally{scope.cancel();deleteJobTree(root)}
    }
    @Test fun `删除停止失败后运行任务保留监控并正常通知`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-delete-failed").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val stopping=CompletableDeferred<Unit>();val releaseStop=CompletableDeferred<Unit>();val notified=CompletableDeferred<Unit>()
        val done=java.util.concurrent.atomic.AtomicBoolean(false);val held=java.util.concurrent.atomic.AtomicInteger()
        val encoded=java.util.Base64.getEncoder().encodeToString("/termux/jobs/job-test".toByteArray())
        val handle="NEXARA_JOB_V1\n$encoded\n12345\n"
        val backend=com.promenar.nexara.data.termux.TermuxBackgroundJobs {request ->
            when {
                "launch=" in request.command -> com.promenar.nexara.data.termux.TermuxCommandResult(stdout=handle,exitCode=0)
                "kill -TERM --" in request.command -> {stopping.complete(Unit);releaseStop.await();com.promenar.nexara.data.termux.TermuxCommandResult(errorCode="TERMUX_PERMISSION_DENIED")}
                else -> com.promenar.nexara.data.termux.TermuxCommandResult(stdout="NEXARA_JOB_V1\n${if(done.get()) "0\n0" else "-\n1"}\n0\n0\n0\n\n",exitCode=0)
            }
        }
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T>{held.incrementAndGet();return try{ExecHeld(block(),ExecForegroundState.STARTED)}finally{held.decrementAndGet()}}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),termux=backend,keepAlive=keep,completion=BackgroundJobCompletion{notified.complete(Unit)})
        try {
            m.startTermux("s","root","true",null,null,1,BackgroundJobExitAction.NOTIFY)
            val deletion=async {runCatching{m.stopSession("s")}.exceptionOrNull()}
            withTimeout(5000){stopping.await()}
            delay(2200)
            assertThat(held.get()).isEqualTo(1)
            releaseStop.complete(Unit)
            assertThat(deletion.await()).isNotNull()
            assertThat(m.list("s").single().running).isTrue()
            done.set(true)
            withTimeout(5000){notified.await()}
            assertThat(m.list("s").single().state).isEqualTo("EXITED")
        } finally {releaseStop.complete(Unit);scope.cancel();deleteJobTree(root)}
    }
    private fun sample()=BackgroundJobRecord("job-a","s","root","SHELL","echo",startedAtMillis=System.currentTimeMillis(),maxRuntimeMinutes=1,onExit="none",logWorkspacePath="jobs/job-a.log",runDirectory="")
    private fun fixture(block:suspend CoroutineScope.(BackgroundJobManager)->Unit)=runBlocking {
        val root=Files.createTempDirectory("job-manager").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")))
        try { block(m) } finally { for(s in listOf("s","t")) m.list(s).filter{it.running}.forEach{m.stop(it.id)};scope.cancel(); root.deleteRecursively() }
    }
    private fun manager(root:File,scope:CoroutineScope,store:BackgroundJobStore,
        reconcile:suspend(BackgroundJobRecord,Boolean,Boolean)->String?={_,_,_->null},
        completion:BackgroundJobCompletion=BackgroundJobCompletion(),keepAlive:ExecKeepAlive=NoExecKeepAlive,
        termux:com.promenar.nexara.data.termux.TermuxBackgroundJobs?=null)=BackgroundJobManager(
        scope,store,File(root,"runs"),
        BuiltinCommandRunner(listOf("/usr/bin/perl","-e","setpgrp(0, 0); exec @ARGV or exit 127"),"/bin/sh",ProcessGroupSignaller {pid,signal->ProcessBuilder("/bin/kill","-$signal","-$pid").start().waitFor()}, {File(root,"scratch")},killGraceMillis=100),
        prepareWorkspace={ BackgroundJobLocation(root,root,false,false) },
        reconcile=reconcile,completion=completion,keepAlive=keepAlive,termux=termux,
        prepareCommand={_,source,stdin,location,minutes -> PreparedBackgroundCommand(BuiltinCommandRequest(listOf("/bin/sh","-c",source),location.commandDirectory,mapOf("PATH" to "/usr/bin:/bin"),stdin,minutes*60)) },
    )
}
