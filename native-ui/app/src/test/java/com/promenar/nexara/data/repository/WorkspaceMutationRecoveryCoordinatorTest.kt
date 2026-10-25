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
    fun `外置身份模式兼容旧journal已删除marker的空stage且拒绝新身份丢失`() = runTest {
        val legacy = fixture(WorkspaceMutationStage.DB_COMMITTED, false, parent.resolve("legacy-terminal"), identityIsExternal = true)
        Files.createDirectories(legacy.target)
        legacy.coordinator.recoverOrThrow()
        assertThat(Files.exists(legacy.target)).isFalse()
        assertThat(legacy.deleted).hasSize(1)

        val modern = fixture(WorkspaceMutationStage.DB_COMMITTED, false, parent.resolve("modern-terminal"),
            identityIsExternal = true, payloadExternal = true)
        Files.createDirectories(modern.target)
        assertConflict(modern)
        assertThat(Files.isDirectory(modern.target)).isTrue()

        val knownExternal = fixture(WorkspaceMutationStage.DB_COMMITTED, false, parent.resolve("known-external"),
            identityIsExternal = true, externalEvidence = true)
        Files.createDirectories(knownExternal.target)
        assertConflict(knownExternal)

        val nonempty = fixture(WorkspaceMutationStage.DB_COMMITTED, false, parent.resolve("nonempty-terminal"), identityIsExternal = true)
        Files.createDirectories(nonempty.target)
        Files.write(nonempty.target.resolve("user-content"), "保留".toByteArray())
        assertConflict(nonempty)
        assertThat(nonempty.target.resolve("user-content").toFile().readText()).isEqualTo("保留")
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
    fun `DB_COMMITTED的stage路径必须与row operationId精确绑定`() = runTest {
        val source = parent.resolve("missing-source")
        val mismatchedTarget = parent.resolve(".nexara_session_deletions/op-b")
        Files.createDirectories(mismatchedTarget)
        val sentinel = Files.write(mismatchedTarget.resolve("sentinel.txt"), "keep".toByteArray())
        val mismatched = fixture(
            stage = WorkspaceMutationStage.DB_COMMITTED,
            dbExists = false,
            source = source,
            operationId = "op-a",
            targetRelative = ".nexara_session_deletions/op-b",
        )

        assertConflict(mismatched)

        assertThat(Files.isDirectory(mismatchedTarget)).isTrue()
        assertThat(Files.readAllBytes(sentinel).toString(Charsets.UTF_8)).isEqualTo("keep")
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

    @Test
    fun `按session恢复不会校验其它根的文件DELETE坏摘要`() = runTest {
        val fixture = fixture(WorkspaceMutationStage.PREPARED, true, createRoot("scoped", "identity"))
        val filePayload = WorkspaceMutationPayloadCodec.encode(
            WorkspaceMutationPayload("file.txt", ".nexara_tombstones/token", "file-id", "other-identity"),
        )
        fixture.entities += fixture.entities.single().copy(
            operationId = "unrelated-file-delete",
            workspaceRootUuid = "other-root",
            payload = filePayload,
            payloadDigest = "invalid",
        )

        fixture.coordinator.recoverSessionOrThrow("session-1")

        assertThat(fixture.deleted).containsExactly("op-scoped")
        assertThat(Files.exists(fixture.source)).isTrue()
    }

    @Test
    fun `损坏session摘要仍可定位session而不可恢复`() = runTest {
        val fixture = fixture(WorkspaceMutationStage.PREPARED, true, createRoot("corrupt", "identity"))
        fixture.entities[0] = fixture.entities.single().copy(payloadDigest = "invalid")

        assertThat(fixture.coordinator.sessionIdOrNull(fixture.entities.single())).isEqualTo("session-1")
        assertThat(runCatching { fixture.coordinator.recoverSessionOrThrow("session-1") }.isFailure).isTrue()
        assertThat(fixture.deleted).isEmpty()
    }

    @Test
    fun `无法定位session的损坏DELETE不在全量恢复中静默消失`() = runTest {
        val fixture = fixture(WorkspaceMutationStage.PREPARED, true, createRoot("unclassified", "identity"))
        fixture.entities[0] = fixture.entities.single().copy(payload = "broken", payloadDigest = Sha256Utils.hash("broken"))

        assertThat(fixture.coordinator.sessionIdOrNull(fixture.entities.single())).isNull()
        assertThat(runCatching { fixture.coordinator.recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(fixture.deleted).isEmpty()
    }

    @Test
    fun `放弃PREPARED只清理稳定journal且保留source内容`() = runTest {
        val fixture = fixture(WorkspaceMutationStage.PREPARED, true, createRoot("abandon", "identity"))

        fixture.coordinator.abandonSessionOrThrow("session-1")

        assertThat(fixture.deleted).containsExactly("op-abandon")
        assertThat(Files.readAllBytes(fixture.source.resolve("payload.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(Files.exists(fixture.target)).isFalse()
    }

    @Test
    fun `放弃session遇到stage残留只拒绝而不回滚或删除文件`() = runTest {
        for (stage in WorkspaceMutationStage.entries) {
            val source = createRoot("abandon-stage-${stage.name}", "identity")
            val fixture = fixture(stage, stage == WorkspaceMutationStage.PREPARED, source)
            Files.createDirectories(fixture.target.parent)
            Files.move(source, fixture.target)

            assertThat(runCatching { fixture.coordinator.abandonSessionOrThrow("session-1") }.isFailure).isTrue()

            assertThat(fixture.deleted).isEmpty()
            assertThat(Files.exists(source)).isFalse()
            assertThat(Files.readAllBytes(fixture.target.resolve("payload.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        }
    }

    @Test
    fun `放弃DB_COMMITTED仅在session和两个磁盘节点均不存在时清理journal`() = runTest {
        val fixture = fixture(WorkspaceMutationStage.DB_COMMITTED, false, parent.resolve("abandon-gone"))

        fixture.coordinator.abandonSessionOrThrow("session-1")

        assertThat(fixture.deleted).containsExactly("op-abandon-gone")
    }

    @Test
    fun `放弃session拒绝CAS快照变化且保留journal与文件`() = runTest {
        val fixture = fixture(WorkspaceMutationStage.PREPARED, true, createRoot("changed", "identity"))
        fixture.beforeAbandon = {
            fixture.entities[0] = fixture.entities.single().copy(updatedAt = 2)
        }

        assertThat(runCatching { fixture.coordinator.abandonSessionOrThrow("session-1") }.isFailure).isTrue()

        assertThat(fixture.deleted).isEmpty()
        assertThat(Files.exists(fixture.source)).isTrue()
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
        operationId: String = "op-${source.fileName}",
        targetRelative: String = ".nexara_session_deletions/$operationId",
        identityIsExternal: Boolean = false,
        payloadExternal: Boolean = false,
        externalEvidence: Boolean = false,
    ): Fixture {
        val payload = WorkspaceMutationPayload(
            sourceRelativePath = sourceRelative,
            targetRelativePath = targetRelative,
            databaseTargetUuid = "session-1",
            expectedSha256 = "identity",
            rootIdentityExternal = payloadExternal,
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
        val entities = mutableListOf(entity)
        lateinit var fixture: Fixture
        val coordinator = WorkspaceMutationRecoveryCoordinator(
            workspaceParent = parent,
            loadUnfinished = { entities.toList() },
            sessionExists = { dbExists },
            deletePrepared = { deleted += it },
            deleteCommitted = { deleted += it },
            verifyRootIdentity = { path, expected ->
                val marker = path.resolve(".identity")
                check(Files.readAllBytes(marker).toString(Charsets.UTF_8) == expected)
            },
            identityMarkerName = ".identity",
            abandonUnchanged = { expected, expectedSessionExists, verifyUnchanged ->
                fixture.beforeAbandon()
                if (expectedSessionExists != dbExists || entities.singleOrNull { it.operationId == expected.operationId } != expected) {
                    false
                } else {
                    verifyUnchanged()
                    deleted += expected.operationId
                    true
                }
            },
            identityIsExternal = identityIsExternal,
            hasExternalRootIdentity = { externalEvidence },
        )
        fixture = Fixture(coordinator, source, parent.resolve(targetRelative), deleted, entities)
        return fixture
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
        val entities: MutableList<WorkspaceMutationEntity>,
        var beforeAbandon: () -> Unit = {},
    )
}
