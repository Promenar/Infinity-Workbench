package com.promenar.infinity.data.ssh

import com.promenar.infinity.data.local.db.dao.SshHostDao
import com.promenar.infinity.data.local.db.entity.SshHostEntity
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.security.SshCredentialStore
import com.promenar.infinity.domain.secret.SshSecretField
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SshAuthType { PASSWORD, KEY }

data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.KEY,
    val jumpAlias: String? = null,
    val trustedKeyType: String? = null,
    val trustedFingerprint: String? = null,
    val keepTerminal: Boolean = false,
    val localForwardPort: Int? = null,
    val forwardHost: String = "127.0.0.1",
    val forwardPort: Int? = null,
) {
    fun executionIdentity(): com.promenar.infinity.domain.tool.SshExecutionIdentity {
        val parts = listOf(hostname, port.toString(), username, authType.name, trustedKeyType.orEmpty(), trustedFingerprint.orEmpty())
        val encoded = kotlinx.serialization.json.JsonArray(parts.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return com.promenar.infinity.domain.tool.SshExecutionIdentity(id, digest)
    }

    init {
        requireAlias(alias)
        requireCleanText(id, "SSH 主机标识无效")
        requireCleanText(hostname, "SSH 主机地址无效")
        requireCleanText(username, "SSH 用户名无效")
        require(port in 1..65535) { "SSH 端口无效" }
        require(jumpAlias == null) { "尚不支持 SSH 跳板" }
        require((localForwardPort == null) == (forwardPort == null)) { "SSH 转发端口必须成对配置" }
        require(localForwardPort == null || localForwardPort in 1..65535) { "SSH 本地转发端口无效" }
        require(forwardPort == null || forwardPort in 1..65535) { "SSH 远端转发端口无效" }
        requireCleanText(forwardHost, "SSH 转发地址无效")
    }
}

data class SshHostKey(val type: String, val fingerprint: String) {
    init {
        requireCleanText(type, "SSH 主机密钥类型无效")
        requireCleanText(fingerprint, "SSH 主机指纹无效")
    }
}

interface SshHostStore {
    fun observeHosts(): Flow<List<SshHost>>
    suspend fun find(alias: String): SshHost?
    suspend fun save(host: SshHost, credential: SshCredential? = null)
    suspend fun delete(alias: String)
    suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost): Boolean
    fun credential(host: SshHost): SshCredential?

    /** 仅替换指定凭据字段；生产实现与主机配置修改共用锁，值由方法负责清零。 */
    suspend fun replaceSecret(hostId: String, field: SshSecretField, value: ByteArray) {
        try {
            val host = observeHosts().first().firstOrNull { it.id == hostId }
                ?: throw IllegalArgumentException("SSH_HOST_NOT_FOUND")
            val previous = credential(host)
            try {
                replacementCredential(previous, field, value).use { save(host, it) }
            } finally { previous?.close() }
        } finally { value.fill(0) }
    }
}

class RoomSshHostStore(private val dao: SshHostDao, secrets: SecretStore) : SshHostStore {
    private val credentials = SshCredentialStore(secrets)

    override fun observeHosts(): Flow<List<SshHost>> = dao.observeHosts().map { hosts -> hosts.map { it.toHost() } }

    override suspend fun find(alias: String): SshHost? {
        requireAlias(alias)
        return dao.find(alias)?.toHost()
    }

    override suspend fun save(host: SshHost, credential: SshCredential?) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val owner = dao.find(host.alias)
            require(owner == null || owner.id == host.id) { "SSH 别名已存在" }
            val previous = dao.findById(host.id)?.toHost()
            val identityChanged = previous == null || previous.hostname != host.hostname || previous.port != host.port ||
                previous.username != host.username || previous.authType != host.authType
            // Room 与 SecretStore 的短提交窗口完成回滚，避免取消留下半写入状态。
            withContext(NonCancellable) {
                if (credential != null || identityChanged) {
                    val binding = host.binding()
                    var retained: SshCredential? = null
                    try {
                        if (credential == null && previous != null && previous.authType == host.authType) {
                            val oldBinding = previous.binding()
                            try { retained = credentials.get(previous.id, oldBinding) } finally { oldBinding.fill(0) }
                        }
                        credentials.replace(host.id, binding, credential ?: retained) { dao.save(host.toEntity()) }
                    } finally {
                        retained?.close()
                        binding.fill(0)
                    }
                } else {
                    dao.save(host.toEntity())
                }
            }
        }
    }

    override suspend fun delete(alias: String) = withContext(Dispatchers.IO) {
        requireAlias(alias)
        mutationMutex.withLock {
            val host = dao.find(alias)?.toHost() ?: return@withLock
            withContext(NonCancellable) {
                val binding = host.binding()
                try { credentials.replace(host.id, binding, null) { dao.deleteById(host.id); Unit } }
                finally { binding.fill(0) }
            }
        }
    }

    override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost): Boolean {
        requireAlias(alias)
        require(expectedHost.alias == alias) { "SSH 主机快照与别名不匹配" }
        if (expectedHost.trustedFingerprint != expectedOld) return false
        return mutationMutex.withLock {
            dao.trust(
                alias = alias, expectedOld = expectedOld, expectedOldKeyType = expectedHost.trustedKeyType,
                keyType = key.type, fingerprint = key.fingerprint, expectedId = expectedHost.id,
                expectedHostname = expectedHost.hostname, expectedPort = expectedHost.port,
                expectedUsername = expectedHost.username, expectedAuthType = expectedHost.authType.name,
            ) == 1
        }
    }

    override fun credential(host: SshHost): SshCredential? {
        val binding = host.binding()
        return try { credentials.get(host.id, binding) } finally { binding.fill(0) }
    }

    override suspend fun replaceSecret(hostId: String, field: SshSecretField, value: ByteArray) {
        try {
            withContext(Dispatchers.IO) {
                mutationMutex.withLock {
                    val host = dao.findById(hostId)?.toHost() ?: throw IllegalArgumentException("SSH_HOST_NOT_FOUND")
                    val binding = host.binding()
                    var previous: SshCredential? = null
                    try {
                        previous = credentials.get(hostId, binding)
                        replacementCredential(previous, field, value).use { replacement ->
                            // 不改写 Room 主机行，保留锁内读取的最新配置、信任状态和其它字段。
                            withContext(NonCancellable) { credentials.replace(hostId, binding, replacement) {} }
                        }
                    } finally { previous?.close(); binding.fill(0) }
                }
            }
        } finally { value.fill(0) }
    }

    private companion object { val mutationMutex = Mutex() }
}

private fun replacementCredential(previous: SshCredential?, field: SshSecretField, value: ByteArray): SshCredential = SshCredential(
    privateKey = previous?.privateKey?.copyOf() ?: ByteArray(0),
    password = if (field == SshSecretField.PASSWORD) value.copyOf() else previous?.password?.copyOf() ?: ByteArray(0),
    passphrase = if (field == SshSecretField.KEY_PASSPHRASE) value.copyOf() else previous?.passphrase?.copyOf() ?: ByteArray(0),
)

private val aliasPattern = Regex("[a-z][a-z0-9_-]{0,47}")
private fun requireAlias(alias: String) = require(aliasPattern.matches(alias)) { "SSH 别名格式无效" }
private fun requireCleanText(value: String, message: String) =
    require(value.isNotBlank() && value.none(Char::isISOControl)) { message }

private fun SshHost.binding(): ByteArray {
    val encoded = listOf(id, hostname, port.toString(), username, authType.name).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    return try { MessageDigest.getInstance("SHA-256").digest(encoded) } finally { encoded.fill(0) }
}

private fun SshHost.toEntity() = SshHostEntity(
    id, alias, hostname, port, username, authType.name, jumpAlias, trustedKeyType, trustedFingerprint,
    keepTerminal, localForwardPort, forwardHost, forwardPort,
)

private fun SshHostEntity.toHost() = SshHost(
    id, alias, hostname, port, username,
    SshAuthType.entries.firstOrNull { it.name == authType } ?: throw IllegalStateException("SSH 认证方式无效"),
    jumpAlias, trustedKeyType, trustedFingerprint,
    keepTerminal, localForwardPort, forwardHost, forwardPort,
)
