package com.promenar.nexara.data.backup

import android.content.Context
import androidx.core.content.ContextCompat
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface BackupStartupState {
    data object Recovering : BackupStartupState
    data object Ready : BackupStartupState
    data object Blocked : BackupStartupState
}

/** 启动期安全恢复闸门；只有 recovery 成功后才允许业务 writer 启动。 */
class BackupRuntime internal constructor(
    private val dataSource: BackupDataSource,
    private val pendingStore: PendingRestoreStore? = null,
    private val codec: BackupPackageCodec? = null,
) {
    private val _state = MutableStateFlow<BackupStartupState>(BackupStartupState.Recovering)
    val state: StateFlow<BackupStartupState> = _state.asStateFlow()

    private val attemptMutex = Mutex()
    private var activeAttempt: CompletableDeferred<BackupStartupState>? = null

    val isWriterGateOpen: Boolean
        get() = state.value == BackupStartupState.Ready

    suspend fun recoverBeforeWriters(): BackupStartupState {
        var ownsAttempt = false
        val attempt = attemptMutex.withLock {
            if (_state.value == BackupStartupState.Ready) return BackupStartupState.Ready
            activeAttempt ?: CompletableDeferred<BackupStartupState>().also {
                activeAttempt = it
                _state.value = BackupStartupState.Recovering
                ownsAttempt = true
            }
        }
        if (!ownsAttempt) return attempt.await()

        return withContext(NonCancellable) {
            val result = try {
                withContext(Dispatchers.IO) { recoverAll() }
                BackupStartupState.Ready
            } catch (_: Exception) {
                BackupStartupState.Blocked
            }
            attemptMutex.withLock {
                _state.value = result
                activeAttempt = null
                attempt.complete(result)
            }
            result
        }
    }

    fun requireWriterGate() {
        if (!isWriterGateOpen) throw BackupStartupException()
    }

    private suspend fun recoverAll() {
        // journal 始终优先；它可能是 pending 对应 restore 在上次崩溃后留下的事务。
        dataSource.recoverInterruptedRestore()
        val store = pendingStore ?: return
        val packageCodec = codec ?: throw BackupValidationException("恢复编解码器不可用")
        val pending = store.read() ?: return
        pending.use { payload ->
            val txId = payload.metadata.txId
            if (dataSource.hasCompletedRestore(txId)) {
                store.clear(txId)
                return
            }
            val validated = packageCodec.decode(payload.packageBytes, payload.password)
            dataSource.restore(validated, txId)
            if (!dataSource.hasCompletedRestore(txId)) {
                throw BackupValidationException("恢复完成回执缺失，待恢复记录已保留")
            }
            store.clear(txId)
        }
    }

    companion object {
        fun createAndroid(
            context: Context,
            database: NexaraDatabase,
            liveSecretStore: SecretStore,
        ): BackupRuntime {
            val appContext = context.applicationContext
            val workspaceBase = appContext.filesDir.resolve("WorkSpace").also { it.mkdirs() }.toPath()
            val sessionWorkspaceBase = appContext.filesDir.resolve("workspaces").also { it.mkdirs() }.toPath()
            val restoreBase = requireNotNull(ContextCompat.getNoBackupFilesDir(appContext)) {
                "noBackupFilesDir 不可用"
            }.resolve("backup-restore-runtime-v1")
                .also { it.mkdirs() }.toPath()
            val preferenceStore = AndroidTransactionalBackupPreferenceStore(appContext)
            val secretStore = AndroidTransactionalBackupSecretStore(appContext, liveSecretStore)
            val dataSource = RoomBackupDataSource(
                    database = database,
                    preferences = preferenceStore,
                    secrets = secretStore,
                    trustedSourceBases = linkedSetOf(
                        appContext.filesDir.toPath(),
                        workspaceBase,
                        sessionWorkspaceBase,
                    ),
                    trustedRestoreBase = restoreBase,
                    appVersion = BuildConfig.VERSION_NAME,
                    journalAuthenticator = AndroidRestoreJournalAuthenticator(),
                )
            return BackupRuntime(
                dataSource = dataSource,
                pendingStore = AndroidPendingRestoreStore(appContext),
                codec = DefaultBackupPackageCodec(),
            )
        }
    }
}

class BackupStartupException internal constructor() :
    IllegalStateException("安全恢复未完成，应用写入已禁用")
