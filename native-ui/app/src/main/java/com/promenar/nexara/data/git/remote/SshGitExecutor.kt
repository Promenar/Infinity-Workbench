package com.promenar.nexara.data.git.remote

import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.data.ssh.SshHostStore
import com.promenar.nexara.data.ssh.SshRuntime
import kotlinx.coroutines.flow.first

/** SSH 桥的生产执行器：按匹配到的主机快照经 JSch 执行；匹配后主机配置变化时拒绝（HOST_CONFIGURATION_CHANGED）。 */
fun sshRuntimeGitExecutor(runtime: SshRuntime): GitSshExecutor = GitSshExecutor { host, command, environment, stdin, stdout, stderr, started ->
    runtime.withHostSnapshot(host) {
        runtime.execStreaming(GitRemoteBroker.SSH_OWNER, host.alias, command, stdin, stdout, stderr, environment, started)
    }
}

suspend fun currentSshHosts(store: SshHostStore): List<SshHost> = store.observeHosts().first()
