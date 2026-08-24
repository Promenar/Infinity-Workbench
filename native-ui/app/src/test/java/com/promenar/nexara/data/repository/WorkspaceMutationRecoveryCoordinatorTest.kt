package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.infra.util.Sha256Utils
import org.junit.Test
import org.junit.After
import org.junit.Before
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest

class WorkspaceMutationRecoveryCoordinatorTest {
    private lateinit var parent: Path

    @Before
    fun setUp() {
        parent = Files.createTempDirectory("workspace-journal-recovery")
    }

    @After
    fun tearDown() {
        if (::parent.isInitialized && Files.exists(parent)) {
            Files.walk(parent).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `PREPARED数据库存在时按文件位置删除或回滚journal`() = runTest {
        val source = createRoot("source", "identity")
        val first = fixture(WorkspaceMutationStage.PREPARED, dbExists = true, source = source)
        first.coordinator.recoverOrThrow()
        assertThat(Files.exists(source)).isTrue()
        assertThat(first.deleted).hasSize(1)

        val stagedSource = createRoot("staged-source", "identity")
        val second = fixture(WorkspaceMutationStage.PREPARED, dbExists = true, source = stagedSource)
        Files.createDirectories(second.target.parent)
        Files.move(stagedSource, second.target)
        second.coordinator.recoverOrThrow()
        assertThat(Files.exists(stagedSource)).isTrue()
        assertThat(Files.exists(second.target)).isFalse()
    }

    @Test
    fun `PREPARED数据库消失或both或neither全部fail-close并保留journal`() = runTest {
        val gone = fixture(WorkspaceMutationStage.PREPARED, dbExists = false, source = createRoot("gone", "identity"))
        assertConflict(gone)

        val both = fixture(WorkspaceMutationStage.PREPARED, dbExists = true, source = createRoot("both", "identity"))
        Files.createDirectories(both.target.parent)
        createRootAt(both.target, "identity")
        assertConflict(both)

        val neitherSource = parent.resolve("neither")
        val neither = fixture(WorkspaceMutationStage.PREPARED, dbExists = true, source = neitherSource)
        assertConflict(neither)
    }

    @Test
    fun `DB_COMMITTED数据库消失时三种合法文件布局全部收敛`() = runTest {
        val staged = fixture(WorkspaceMutationStage.DB_COMMITTED, false, createRoot("staged", "identity"))
        Files.createDirectories(staged.target.parent)
        Files.move(staged.source, staged.target)
        staged.coordinator.recoverOrThrow()
        assertThat(Files.exists(staged.target)).isFalse()

        val neither = fixture(WorkspaceMutationStage.DB_COMMITTED, false, parent.resolve("committed-neither"))
        neither.coordinator.recoverOrThrow()
        assertThat(neither.deleted).hasSize(1)

        val source = fixture(WorkspaceMutationStage.DB_COMMITTED, false, createRoot("committed-source", "identity"))
        source.coordinator.recoverOrThrow()
        assertThat(Files.exists(source.source)).isFalse()
        assertThat(source.deleted).hasSize(1)
    }

    @Test
    fun `DB_COMMITTED的both或数据库仍存在必须fail-close`() = runTest {
        val dbExists = fixture(WorkspaceMutationStage.DB_COMMITTED, true, createRoot("db-exists", "identity"))
        assertConflict(dbExists)
        val both = fixture(WorkspaceMutationStage.DB_COMMITTED, false, createRoot("committed-both", "identity"))
        Files.createDirectories(both.target.parent)
        createRootAt(both.target, "identity")
        assertConflict(both)
    }

    @Test
    fun `路径穿越与符号链接源必须拒绝且不改文件`() = runTest {
        val outside = createRoot("outside", "identity")
        val traversal = fixture(
            WorkspaceMutationStage.PREPARED,
            true,
            outside,
            sourceRelative = "../outside",
        )
        assertConflict(traversal)
        assertThat(Files.exists(outside)).isTrue()

        val real = createRoot("real", "identity")
        val link = parent.resolve("link")
        Files.createSymbolicLink(link, real)
        val symlink = fixture(WorkspaceMutationStage.PREPARED, true, link)
        assertConflict(symlink)
        assertThat(Files.isSymbolicLink(link)).isTrue()
    }

    private suspend fun assertConflict(fixture: Fixture) {
        assertThat(runCatching { fixture.coordinator.recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(fixture.deleted).isEmpty()
    }

    private fun fixture(
        stage: WorkspaceMutationStage,
        dbExists: Boolean,
        source: Path,
        sourceRelative: String = source.fileName.toString(),
    ): Fixture {
        val operationId = "op-${source.fileName}"
        val payload = WorkspaceMutationPayload(
            sourceRelativePath = sourceRelative,
            targetRelativePath = ".nexara_session_deletions/$operationId",
            databaseTargetUuid = "session-1",
            expectedSha256 = "identity",
        )
        val raw = WorkspaceMutationPayloadCodec.encode(payload)
        val entity = WorkspaceMutationEntity(
            operationId = operationId,
            workspaceRootUuid = "root-1",
            operationType = WorkspaceMutationType.DELETE,
            payload = raw,
            payloadDigest = Sha256Utils.hash(raw),
            state = stage,
            createdAt = 1,
            updatedAt = 1,
        )
        val deleted = mutableListOf<String>()
        val coordinator = WorkspaceMutationRecoveryCoordinator(
            workspaceParent = parent,
            loadUnfinished = { listOf(entity) },
            sessionExists = { dbExists },
            deletePrepared = { deleted += it },
            deleteCommitted = { deleted += it },
            verifyRootIdentity = { path, expected ->
                val marker = path.resolve(".identity")
                check(Files.readAllBytes(marker).toString(Charsets.UTF_8) == expected)
            },
        )
        return Fixture(coordinator, source, parent.resolve(".nexara_session_deletions/$operationId"), deleted)
    }

    private fun createRoot(name: String, identity: String): Path =
        createRootAt(parent.resolve(name), identity)

    private fun createRootAt(path: Path, identity: String): Path {
        Files.createDirectories(path)
        Files.write(path.resolve(".identity"), identity.toByteArray())
        Files.write(path.resolve("payload.txt"), "payload".toByteArray())
        return path
    }

    private data class Fixture(
        val coordinator: WorkspaceMutationRecoveryCoordinator,
        val source: Path,
        val target: Path,
        val deleted: MutableList<String>,
    )
}
