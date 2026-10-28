package com.promenar.nexara.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.PersistentFileIdentity
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class LinuxWorkspaceFileTargetTest {
    private val root = Files.createTempDirectory("linux-media-target").toFile().canonicalFile
    private val identity = PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
    private val record = WorkspaceEntity("linux", WorkspaceEntity.KIND_LINUX_HOME, "app", "/opt/app#$identity", 0)
    private val directory = SessionWorkspace.Directory(record, root)
    private val backend = mockk<ExternalFolderBackend>()
    private val targets = WorkspaceFileTargetResolver(mockk<ISessionRepository>(), mockk<IWorkspaceRepository>(),
        mockk<ExternalWorkspaceAccess>(), { directory }, root, directoryBackend = { backend }, pathPresenter = LinuxWorkspaceFilePathPresenter)
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun `媒体实际目录和相对路径均绑定当前目录并可重新校验`() = runBlocking {
        val selected = targets.resolve("s", "/opt/app/media/image.png", writing = true)
        assertThat(selected.directory).isEqualTo(root)
        assertThat(selected.relative("/opt/app/media/image.png")).isEqualTo("/media/image.png")
        assertThat(selected.virtualRoot).isEqualTo("/opt/app")
        targets.checkCurrent(selected, "/opt/app/media/image.png")
        val relative = targets.resolve("s", "media/image.png", writing = true)
        assertThat(relative.identity).isEqualTo(selected.identity)
        assertThat(targets.resolve("s", "/workspace/media/image.png", writing = true).identity).isEqualTo(selected.identity)
    }
}
