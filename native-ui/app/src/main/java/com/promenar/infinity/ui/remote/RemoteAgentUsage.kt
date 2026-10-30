package com.promenar.infinity.ui.remote

import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.ssh.SshAgentPtySession
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.ui.chat.toolInvocations
import com.promenar.infinity.ui.ssh.SshReadOnlyTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 本会话智能体正在运行的一条 ssh_exec；[stepId] 即实时输出键。 */
internal data class RemoteAgentExec(val stepId: String, val host: String, val command: String)

/** “智能体正在使用”分区的内容：前台命令、交互会话与 SSH 后台命令。 */
internal data class RemoteAgentUsage(
    val execs: List<RemoteAgentExec> = emptyList(),
    val sessions: List<SshAgentPtySession> = emptyList(),
    val jobs: List<BackgroundJob> = emptyList(),
) {
    val active: Boolean get() = execs.isNotEmpty() || sessions.isNotEmpty() || jobs.isNotEmpty()

    /** 某台主机的只读终端页签：先命令后交互会话，按出现顺序。 */
    fun readOnlyTabs(alias: String): List<SshReadOnlyTab> =
        execs.filter { it.host == alias }.map { SshReadOnlyTab("exec:${it.stepId}", SshReadOnlyTab.Kind.EXEC, it.stepId, it.command) } +
            sessions.filter { it.alias == alias }.map { SshReadOnlyTab("pty:${it.id}", SshReadOnlyTab.Kind.SESSION, it.liveOutputKey, null) }
}

private const val SSH_EXEC = "ssh_exec"

/**
 * 从会话各条消息的执行步骤推导运行中的 ssh_exec：还没有结果，且已登记实时输出通道
 * （审批后由审批流程执行的调用也在其中），或会话仍在生成。
 */
internal fun runningSshExecs(stepsByMessage: List<List<ExecutionStep>>, liveKeys: Set<String>, generating: Boolean): List<RemoteAgentExec> =
    stepsByMessage.flatMap { steps ->
        toolInvocations(steps).mapNotNull { invocation ->
            if (invocation.name != SSH_EXEC || invocation.complete) return@mapNotNull null
            val call = invocation.call ?: return@mapNotNull null
            val stepId = call.id.substringAfter("::")
            if (stepId !in liveKeys && !generating) return@mapNotNull null
            val args = runCatching { Json.parseToJsonElement(call.toolArgs ?: "") as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val host = (args["host"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val command = (args["command"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            RemoteAgentExec(stepId, host, command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty())
        }
    }.distinctBy { it.stepId }

/** 本会话仍在运行的 SSH 后台命令。 */
internal fun runningSshJobs(jobs: List<BackgroundJob>, sessionId: String): List<BackgroundJob> =
    jobs.filter { it.sessionId == sessionId && it.runner == BackgroundJobRunner.SSH && it.state == BackgroundJobState.RUNNING }
