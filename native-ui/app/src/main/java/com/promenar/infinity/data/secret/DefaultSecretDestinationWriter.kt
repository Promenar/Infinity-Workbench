package com.promenar.infinity.data.secret

import com.promenar.infinity.data.git.remote.GitCredentialHost
import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.data.jobs.BackgroundJobManager
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.domain.secret.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 不经消息或工具参数转交值，沿用各存储原有的加密与身份绑定入口。 */
class DefaultSecretDestinationWriter(
    private val git: () -> GitCredentialStore,
    private val hosts: SshHostStore,
    private val provider: () -> ProviderManager,
    private val jobs: () -> BackgroundJobManager,
    private val vault: SessionSecretVault,
    private val githubLoginAvailable: Boolean = true,
) : SecretDestinationWriter {
    /** 模型只看得到 SSH 别名，界面另存为传主机 ID；两者都接受，写入一律用解析出的 ID。 */
    private suspend fun host(ref: String): SshHost = hosts.observeHosts().first().let { all ->
        all.firstOrNull { it.id == ref } ?: all.firstOrNull { it.alias == ref }
    } ?: throw SecretIoFailure("SECRET_SSH_HOST_NOT_FOUND")

    /** 模型只看得到服务商名称，界面另存为传服务商 ID；名称忽略大小写且必须唯一。 */
    private fun providerId(ref: String): String {
        val manager = provider()
        if (manager.getProviderSummary(ref) != null) return ref
        val items = manager.providers.value.ifEmpty { manager.loadProviders(); manager.providers.value }
        val matches = items.filter { it.name.equals(ref.trim(), ignoreCase = true) }
        return when (matches.size) {
            1 -> matches.single().id
            0 -> throw SecretIoFailure("SECRET_PROVIDER_NOT_FOUND")
            else -> throw SecretIoFailure("SECRET_PROVIDER_AMBIGUOUS")
        }
    }

    override suspend fun describe(sessionId: String, destination: SecretDestination): SecretDestinationInfo = withContext(Dispatchers.IO) {
        when (destination) {
            is SecretDestination.GitCredential -> {
                val key = GitCredentialHost.normalize(destination.host) ?: throw SecretIoFailure("SECRET_INVALID_GIT_HOST")
                SecretDestinationInfo("Git 凭据 · $key", destination.username == null && git().username(key) == null,
                    githubLoginAvailable && key == GitCredentialStore.GITHUB_HOST)
            }
            is SecretDestination.SshHost -> {
                val host = host(destination.hostId)
                SecretDestinationInfo("SSH 主机 ${host.alias} · " + if (destination.field == SshSecretField.PASSWORD) "登录密码" else "私钥口令")
            }
            is SecretDestination.ProviderApiKey -> {
                val summary = provider().getProviderSummary(providerId(destination.providerId)) ?: throw SecretIoFailure("SECRET_PROVIDER_NOT_FOUND")
                if (summary.protocolType is com.promenar.infinity.data.remote.protocol.ProtocolType.Google_VertexAI)
                    throw SecretIoFailure("SECRET_PROVIDER_API_KEY_UNSUPPORTED")
                SecretDestinationInfo("模型服务商 ${summary.name} · API Key")
            }
            is SecretDestination.JobInput -> {
                val job = jobs().list(sessionId).firstOrNull { it.id == destination.jobId } ?: throw SecretIoFailure("SECRET_JOB_NOT_FOUND")
                if (!job.running) throw SecretIoFailure("SECRET_JOB_NOT_RUNNING")
                if (job.runnerKind != com.promenar.infinity.domain.jobs.BackgroundJobRunner.LINUX)
                    throw SecretIoFailure("SECRET_JOB_INPUT_UNSUPPORTED")
                SecretDestinationInfo("后台任务 ${job.id} 的终端")
            }
            is SecretDestination.SessionEnv -> {
                if (!SecretIoLimits.SLOT_NAME.matches(destination.name)) throw SecretIoFailure("SECRET_INVALID_SLOT_NAME")
                SecretDestinationInfo("本会话环境变量 $${destination.name}")
            }
        }
    }

    override suspend fun satisfiedByGitHubLogin(destination: SecretDestination): Boolean = withContext(Dispatchers.IO) {
        destination is SecretDestination.GitCredential &&
            GitCredentialHost.normalize(destination.host) == GitCredentialStore.GITHUB_HOST &&
            git().credentials.value.any { it.host == GitCredentialStore.GITHUB_HOST && it.fromGitHubLogin }
    }

    override suspend fun write(sessionId: String, destination: SecretDestination, value: ByteArray, username: String?) = withContext(Dispatchers.IO) {
        describe(sessionId, destination)
        when (destination) {
            is SecretDestination.GitCredential -> {
                val key = GitCredentialHost.normalize(destination.host) ?: throw SecretIoFailure("SECRET_INVALID_GIT_HOST")
                val user = destination.username ?: username?.takeIf { it.isNotBlank() } ?: git().username(key)
                    ?: throw SecretIoFailure("SECRET_USERNAME_REQUIRED")
                git().save(key, user, value)
            }
            is SecretDestination.SshHost -> hosts.replaceSecret(host(destination.hostId).id, destination.field, value)
            is SecretDestination.ProviderApiKey -> provider().replaceApiKey(providerId(destination.providerId), value)
            is SecretDestination.JobInput -> { jobs().input(sessionId, destination.jobId, value.copyOf()); Unit }
            is SecretDestination.SessionEnv -> vault.put(sessionId, destination.name, destination.name.take(SecretIoLimits.MAX_LABEL_CHARS), SecretSource.UserInput, value)
        }
    }
}
