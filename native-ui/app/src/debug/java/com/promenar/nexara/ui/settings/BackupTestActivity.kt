package com.promenar.nexara.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.PendingRestoreMetadata
import com.promenar.nexara.data.backup.PendingRestorePhase
import com.promenar.nexara.data.remote.webdav.RemoteBackup
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import com.promenar.nexara.data.repository.BackupUploadReceipt
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.ui.theme.NexaraTheme
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred

class BackupTestActivity : ComponentActivity() {
    var listGate = CompletableDeferred(Unit)
    lateinit var testViewModel: BackupViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testViewModel = BackupViewModel(
            operations = FakeBackupOperations(),
            settings = MemoryBackupSettings(),
            secrets = MemorySecrets(),
            restartRequester = BackupRestartRequester {},
            synchronousIoForTests = true,
        )
        testViewModel.saveWebDavConfig("https://dav.example.test/", "user", "password".toCharArray())
        testViewModel.setWebdavEnabled(true)
        setContent { NexaraTheme { BackupSettingsScreen(onNavigateBack = {}, viewModel = testViewModel) } }
    }

    private inner class FakeBackupOperations : BackupOperations {
        private val remote = RemoteBackup("remote-full-id.nexara", 42, 1234, "strong-etag")
        override suspend fun export(options: BackupExportOptions) = byteArrayOf(1)
        override suspend fun upload(config: WebDavConfig, options: BackupExportOptions) = BackupUploadReceipt("remote", null)
        override suspend fun testRemote(config: WebDavConfig) = Result.success(Unit)
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> {
            listGate.await()
            return listOf(remote)
        }
        override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?) = metadata(operationId)
        override suspend fun stageRemoteRestore(operationId: String, config: WebDavConfig, selected: RemoteBackup, password: CharArray?) = metadata(operationId)
        override suspend fun discardPendingRestore(operationId: String) = Unit
        override suspend fun authorizePendingRestore(operationId: String) = Unit
        private fun metadata(id: String) = PendingRestoreMetadata(id, byteArrayOf(1), PendingRestorePhase.STAGED)
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId) = values[id]?.copyOf()
        override fun contains(id: SecretId) = id in values
        override fun remove(id: SecretId) { values.remove(id) }
    }

    private class MemoryBackupSettings : BackupSettingsStore {
        override var webDavEnabled = false
        override var webDavUrl = ""
        override var webDavUser = ""
        override var lastBackupTime = 0L
        override var webDavPasswordPlaintext: String? = null
    }
}
