package com.promenar.infinity.data.ssh

enum class SshConnectionKind { TERMINAL, FORWARD, AGENT, JOB }

data class SshActiveConnection(
    val owner: String,
    val alias: String,
    val kind: SshConnectionKind,
    /** AGENT/JOB 所属聊天会话；其它为 null。 */
    val sessionId: String?,
    /** JOB 对应的后台任务号；其它为 null。 */
    val jobId: String?,
    val connectedAtMillis: Long,
)

data class SshAgentPtySession(
    val id: String,
    val sessionId: String,
    val alias: String,
    /** ToolLiveOutputStore 中的只读镜像键。 */
    val liveOutputKey: String,
    val openedAtMillis: Long,
)

data class SshRuntimeActivity(
    val connections: List<SshActiveConnection> = emptyList(),
    val agentSessions: List<SshAgentPtySession> = emptyList(),
)
