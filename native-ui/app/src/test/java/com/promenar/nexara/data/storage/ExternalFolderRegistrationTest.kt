package com.promenar.nexara.data.storage

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.ExternalMountDao
import com.promenar.nexara.data.local.db.entity.ExternalMountEntity
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalFolderException
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ExternalFolderRegistrationTest {
    private val directory = Files.createTempDirectory("external-registration").toFile()
    private val resolver = mockk<ContentResolver> {
        every { persistedUriPermissions } returns emptyList()
        every { takePersistableUriPermission(any(), any()) } just Runs
    }
    private val context = mockk<Context> {
        every { noBackupFilesDir } returns directory
        every { contentResolver } returns resolver
    }
    private val rows = mutableListOf<ExternalMountEntity>()
    private val dao = mockk<ExternalMountDao> {
        coEvery { all() } answers { rows.toList() }
        coEvery { get(id = any()) } answers { rows.firstOrNull { it.id == firstArg<String>() } }
        coEvery { insert(any()) } coAnswers { rows.add(firstArg()); Unit }
        coEvery { delete(any()) } coAnswers { val id = firstArg<String>(); rows.removeAll { it.id == id }; Unit }
        coEvery { update(any()) } coAnswers {
            val row = firstArg<ExternalMountEntity>()
            rows[rows.indexOfFirst { it.id == row.id }] = row
        }
    }
    private val manager = ExternalFolderManager(context, dao, mockk<IWorkspaceRepository>(), FileIndexEventSink.None) { TransferMemoryBackend() }
    private val source = "content://test/tree/root"
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val readWrite = read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `新增SAF挂载要求读写授权且同别名可分别属于两个会话`() = runBlocking {
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.addSaf("docs", "session-a", source, read) }
        }.code).isEqualTo("READ_ONLY")
        assertThat(rows).isEmpty()
        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
        val first = manager.addSaf("docs", "session-a", source, readWrite)
        val second = manager.addSaf("docs", "session-b", source, readWrite)
        assertThat(first.sessionId).isEqualTo("session-a")
        assertThat(second.sessionId).isEqualTo("session-b")
        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(rows.map { it.readOnly }).containsExactly(false, false)
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.addSaf("docs", "session-a", source, readWrite) }
        }.code).isEqualTo("CONFLICT")
        verify(exactly = 2) { resolver.takePersistableUriPermission(Uri.parse(source), readWrite) }
    }

    @Test fun `历史挂载重新授权也必须获得写权限`() = runBlocking {
        val mount = manager.addSaf("docs", "session", source, readWrite)
        rows[0] = rows.single().copy(unavailableReason = "授权失效")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.reauthorize(mount.id, source, read) }
        }.code).isEqualTo("READ_ONLY")
        assertThat(rows.single().unavailableReason).isNotNull()
        manager.reauthorize(mount.id, source, readWrite)
        assertThat(rows.single().unavailableReason).isNull()
    }

    @Test fun `共享SAF来源在删除最后一个会话挂载后才释放授权`() = runBlocking {
        val permission = mockk<UriPermission> {
            every { uri } returns Uri.parse(source)
            every { isReadPermission } returns true
            every { isWritePermission } returns true
        }
        every { resolver.persistedUriPermissions } returns listOf(permission)
        every { resolver.releasePersistableUriPermission(any(), any()) } just Runs
        manager.addSaf("docs", "session-a", source, readWrite)
        val second = manager.addSaf("docs", "session-b", source, readWrite)
        manager.deleteSessionMounts("session-a")
        assertThat(rows.map { it.id }).containsExactly(second.id)
        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
        manager.deleteSessionMounts("session-b")
        assertThat(rows).isEmpty()
        verify(exactly = 1) { resolver.releasePersistableUriPermission(Uri.parse(source), readWrite) }
    }
}
