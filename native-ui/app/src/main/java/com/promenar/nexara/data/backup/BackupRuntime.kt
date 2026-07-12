package com.promenar.nexara.data.backup

import android.content.Context
import androidx.core.content.ContextCompat
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 启动期安全恢复闸门；只有 recovery 成功后才允许业务 writer 启动。 */
class BackupRuntime internal constructor(
    private val dataSource: BackupDataSource,
) {
    @Volatile
    private var state: State = State.NEW

    val isWriterGateOpen: Boolean
        get() = state == State.READY

    fun recoverBeforeWriters(timeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MILLIS) = synchronized(lock) {
        if (state == State.READY) return@synchronized
        if (state == State.BLOCKED) throw BackupStartupException()
        require(timeoutMillis > 0) { "安全恢复超时必须大于 0" }
        state = State.RECOVERING
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "nexara-backup-startup-recovery").apply { isDaemon = true }
        }
        val recovery = executor.submit<Unit> {
            runBlocking { dataSource.recoverInterruptedRestore() }
        }
        try {
            recovery.get(timeoutMillis, TimeUnit.MILLISECONDS)
            state = State.READY
        } catch (error: ExecutionException) {
            state = State.BLOCKED
            val cause = error.cause
            if (cause is Error) throw cause
            throw BackupStartupException()
        } catch (_: TimeoutException) {
            state = State.BLOCKED
            throw BackupStartupException()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            state = State.BLOCKED
            throw BackupStartupException()
        } finally {
            recovery.cancel(true)
            executor.shutdownNow()
        }
    }

    fun requireWriterGate() {
        if (!isWriterGateOpen) throw BackupStartupException()
    }

    private val lock = Any()

    private enum class State { NEW, RECOVERING, READY, BLOCKED }

    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MILLIS = 5_000L

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
            return BackupRuntime(
                RoomBackupDataSource(
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
            )
        }
    }
}

class BackupStartupException internal constructor() :
    IllegalStateException("安全恢复未完成，应用写入已禁用")
