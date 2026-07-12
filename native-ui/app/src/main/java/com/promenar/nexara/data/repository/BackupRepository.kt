package com.promenar.nexara.data.repository

import android.content.Context
import androidx.core.content.ContextCompat
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.backup.AndroidPendingRestoreStore
import com.promenar.nexara.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.nexara.data.backup.AndroidTransactionalBackupPreferenceStore
import com.promenar.nexara.data.backup.AndroidTransactionalBackupSecretStore
import com.promenar.nexara.data.backup.BackupContent
import com.promenar.nexara.data.backup.BackupDataSource
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.BackupOptions
import com.promenar.nexara.data.backup.BackupPackageCodec
import com.promenar.nexara.data.backup.BackupPackageLimits
import com.promenar.nexara.data.backup.BackupValidationException
import com.promenar.nexara.data.backup.DefaultBackupPackageCodec
import com.promenar.nexara.data.backup.PendingRestoreMetadata
import com.promenar.nexara.data.backup.PendingRestoreStore
import com.promenar.nexara.data.backup.RoomBackupDataSource
import com.promenar.nexara.data.backup.wipe
import com.promenar.nexara.data.backup.WipeableByteArrayOutputStream
import com.promenar.nexara.data.remote.webdav.KtorWebDavBackupClient
import com.promenar.nexara.data.remote.webdav.RemoteBackup
import com.promenar.nexara.data.remote.webdav.UploadAndPruneResult
import com.promenar.nexara.data.remote.webdav.WebDavBackupClient
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import com.promenar.nexara.data.remote.webdav.WebDavPruneWarning
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupUploadReceipt(
    val fileName: String,
    val pruneWarning: WebDavPruneWarning?,
)

/**
 * 备份用例边界。只接受 typed 参数，并委托 Task 5/6/7 的安全实现；不感知 UI 状态或 DAO。
 * 返回的包字节由调用方拥有，调用方使用完毕后负责擦除。
 */
class BackupRepository internal constructor(
    private val dataSource: BackupDataSource,
    private val codec: BackupPackageCodec,
    private val webDav: WebDavBackupClient,
    private val pendingStore: PendingRestoreStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        dataSource = createDataSource(context),
        codec = DefaultBackupPackageCodec(),
        webDav = KtorWebDavBackupClient(),
        pendingStore = AndroidPendingRestoreStore(context),
    )

    suspend fun export(options: BackupExportOptions = BackupExportOptions()): ByteArray = withContext(Dispatchers.IO) {
        val password = validatedPassword(options)
        val content = CANONICAL_CONTENT + if (options.includeSecrets) setOf(BackupContent.SECRETS) else emptySet()
        var snapshot: com.promenar.nexara.data.backup.BackupSnapshot? = null
        try {
            val captured = dataSource.snapshot(content)
            snapshot = captured
            codec.encode(captured, BackupOptions(content, options.includeSecrets, password))
                .also(::requireBoundedPackage)
        } finally {
            snapshot?.wipe()
            password?.fill('\u0000')
        }
    }

    suspend fun upload(config: WebDavConfig, options: BackupExportOptions = BackupExportOptions()): BackupUploadReceipt {
        val bytes = export(options)
        try {
            val fileName = backupFileName(clock())
            val result = webDav.uploadAndPrune(config, fileName, bytes, keep = REMOTE_KEEP)
            return when (result) {
                is UploadAndPruneResult.Committed -> BackupUploadReceipt(result.fileName, result.pruneWarning)
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun testRemote(config: WebDavConfig): Result<Unit> = webDav.test(config)

    suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> = webDav.list(config)

    suspend fun downloadRemote(config: WebDavConfig, selected: RemoteBackup): ByteArray {
        val current = webDav.list(config).singleOrNull { it == selected }
            ?: throw BackupValidationException("所选远程备份已变化，请刷新列表后重试")
        return webDav.download(config, current).also(::requireBoundedPackage)
    }

    suspend fun stageLocalRestore(input: InputStream, password: CharArray? = null): PendingRestoreMetadata =
        stageLocalRestore(UUID.randomUUID().toString(), input, password)

    suspend fun stageLocalRestore(
        operationId: String,
        input: InputStream,
        password: CharArray? = null,
    ): PendingRestoreMetadata =
        withContext(Dispatchers.IO) {
            pendingStore.begin(operationId)
            val bytes = readBounded(input)
            try {
                stageValidated(operationId, bytes, password)
            } finally {
                bytes.fill(0)
            }
        }

    suspend fun stageRemoteRestore(
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray? = null,
    ): PendingRestoreMetadata = stageRemoteRestore(UUID.randomUUID().toString(), config, selected, password)

    suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray? = null,
    ): PendingRestoreMetadata = withContext(Dispatchers.IO) {
        pendingStore.begin(operationId)
        val bytes = downloadRemote(config, selected)
        try {
            stageValidated(operationId, bytes, password)
        } finally {
            bytes.fill(0)
        }
    }

    fun stageValidated(packageBytes: ByteArray, password: CharArray? = null): PendingRestoreMetadata {
        return stageValidated(UUID.randomUUID().toString(), packageBytes, password)
    }

    fun stageValidated(operationId: String, packageBytes: ByteArray, password: CharArray? = null): PendingRestoreMetadata {
        requireBoundedPackage(packageBytes)
        pendingStore.begin(operationId)
        val ownedPassword = password?.copyOf()
        try {
            codec.decode(packageBytes, ownedPassword).use { /* 验证先行，运行中绝不写 Operational 数据 */ }
            return pendingStore.stage(operationId, packageBytes, ownedPassword)
        } finally {
            ownedPassword?.fill('\u0000')
        }
    }

    suspend fun discardPendingRestore(operationId: String) = withContext(Dispatchers.IO) {
        pendingStore.cancel(operationId)
    }

    suspend fun authorizePendingRestore(operationId: String) = withContext(Dispatchers.IO) {
        pendingStore.authorize(operationId)
    }

    private fun validatedPassword(options: BackupExportOptions): CharArray? {
        if (!options.includeSecrets) return null
        val password = options.password ?: throw BackupValidationException("包含密钥时必须设置备份密码")
        val confirmation = options.passwordConfirmation ?: throw BackupValidationException("包含密钥时必须确认备份密码")
        if (password.isEmpty() || confirmation.isEmpty()) throw BackupValidationException("备份密码不能为空")
        var difference = password.size xor confirmation.size
        val length = maxOf(password.size, confirmation.size)
        for (index in 0 until length) {
            difference = difference or ((password.getOrNull(index)?.code ?: 0) xor
                (confirmation.getOrNull(index)?.code ?: 0))
        }
        if (difference != 0) throw BackupValidationException("两次输入的备份密码不一致")
        return password.copyOf()
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = WipeableByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size().toLong() + count > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
                    throw BackupValidationException("本地备份包超过 16 MiB 安全限制")
                }
                output.write(buffer, 0, count)
            }
            val result = output.toByteArray()
            try {
                requireBoundedPackage(result)
                return result
            } catch (error: Throwable) {
                result.fill(0)
                throw error
            }
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private fun requireBoundedPackage(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size.toLong() > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("备份包大小无效")
        }
    }

    private fun backupFileName(timestamp: Long): String =
        "nexara_backup_${timestamp.toString().padStart(13, '0')}.nexara"

    companion object {
        val CANONICAL_CONTENT = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
        const val REMOTE_KEEP = 5

        private fun createDataSource(context: Context): BackupDataSource {
            val app = context.applicationContext as NexaraApplication
            val restoreBase = requireNotNull(ContextCompat.getNoBackupFilesDir(app))
                .resolve("backup-restore-runtime-v1").also { it.mkdirs() }.toPath()
            return RoomBackupDataSource(
                database = app.database,
                preferences = AndroidTransactionalBackupPreferenceStore(app),
                secrets = AndroidTransactionalBackupSecretStore(app, app.secretStore),
                trustedSourceBases = linkedSetOf(
                    app.filesDir.toPath(),
                    app.filesDir.resolve("WorkSpace").also { it.mkdirs() }.toPath(),
                    app.filesDir.resolve("workspaces").also { it.mkdirs() }.toPath(),
                ),
                trustedRestoreBase = restoreBase,
                appVersion = BuildConfig.VERSION_NAME,
                journalAuthenticator = AndroidRestoreJournalAuthenticator(),
            )
        }
    }
}
