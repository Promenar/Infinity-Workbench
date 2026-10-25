package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadResult
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 身份格式改为 nx1 之前认领的根：数据库保存 `nonce|sha256(完整fileKey)`，目录属性保存同一 nonce。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LegacyRootIdentityAdoptionTest {
    private lateinit var database: NexaraDatabase
    private lateinit var root: Path
    private lateinit var nonce: String
    private lateinit var rawKey: String
    private var device: String? = null
    private lateinit var fileOps: SecureWorkspaceFileOps

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = Files.createTempDirectory("legacy-root-identity").toFile().canonicalFile.toPath()
        nonce = UUID.randomUUID().toString()
        rawKey = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()
        fileOps = SecureWorkspaceFileOps(
            directoryOpener = { IdentityTestDirectory(it) },
            identityFileKey = { key -> device?.let { key.toString().replace(Regex("dev=[^,)]+"), "dev=$it") } ?: key },
            rootNonceReader = { _, _ -> nonce },
        )
        database.fileEntryDao().insertAbort(rootEntry(legacyIdentity(nonce, rawKey)))
    }

    @After
    fun tearDown() {
        database.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `旧格式根认领按旧算法核验后改写为等价身份且维护清理通过`() = runTest {
        coordinator().recoverRootOrThrow("root")

        assertThat(rootHash()).isEqualTo(PersistentFileIdentity.encode(nonce, rawKey))
        coordinator().recoverOrThrow()
        assertThat(rootHash()).isEqualTo(PersistentFileIdentity.encode(nonce, rawKey))
    }

    @Test
    fun `旧格式根的未完成journal随根认领一起改写并完成恢复`() = runTest {
        database.fileEntryDao().insertAbort(fileEntry("file", "/same.txt", "same.txt"))
        Files.write(root.resolve("same.txt"), "payload".toByteArray())
        insertJournal(WorkspaceMutationType.MOVE, "same.txt", "same.txt", legacyIdentity(nonce, rawKey))

        coordinator().recoverRootOrThrow("root")

        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
        assertThat(Files.readAllBytes(root.resolve("same.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
    }

    @Test
    fun `放弃旧格式根上的创建journal时先迁移身份再按现行规则保留证据`() = runTest {
        insertJournal(WorkspaceMutationType.CREATE, "created.txt", "created.txt", legacyIdentity(nonce, rawKey))

        val failure = assertThrows(WorkspaceMutationRecoveryException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator().abandonRootOrThrow("root") }
        }

        assertThat(failure.message).isEqualTo("该 journal 类型需要保留内部恢复证据，不能安全放弃")
        val journal = requireNotNull(database.workspaceMutationDao().get("op-1"))
        assertThat(payload(journal).expectedSha256).isEqualTo(PersistentFileIdentity.encode(nonce, rawKey))
        assertThat(journal.payloadDigest).isEqualTo(Sha256Utils.hash(journal.payload))
    }

    @Test
    fun `设备号已变无法按旧算法核验时保留数据库与journal并说明原因`() = runTest {
        insertJournal(WorkspaceMutationType.MOVE, "same.txt", "same.txt", legacyIdentity(nonce, rawKey))
        val before = database.workspaceMutationDao().get("op-1")
        device = "123"

        val failure = assertThrows(WorkspaceMutationRecoveryException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator().recoverRootOrThrow("root") }
        }

        assertThat(failure.message).contains("旧版身份格式")
        assertThat(rootHash()).isEqualTo(legacyIdentity(nonce, rawKey))
        assertThat(database.workspaceMutationDao().get("op-1")).isEqualTo(before)
    }

    @Test
    fun `目录nonce与旧认领不同即被替换的目录不迁移`() = runTest {
        val claimed = legacyIdentity(UUID.randomUUID().toString(), rawKey)
        database.fileEntryDao().update(rootEntry(claimed))

        assertThrows(WorkspaceMutationRecoveryException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator().recoverOrThrow() }
        }

        assertThat(rootHash()).isEqualTo(claimed)
    }

    @Test
    fun `私有区在数据库改写前迁到新身份下`() = runTest {
        val moved = mutableListOf<Pair<String, String>>()
        val ops = object : WorkspaceFileOps by fileOps {
            override fun adoptLegacyRootPrivateData(legacyIdentity: String, identity: String) {
                // 中断在此处时数据库仍是旧认领，下次启动可以重新核验并继续。
                assertThat(kotlinx.coroutines.runBlocking { rootHash() }).isEqualTo(legacyIdentity)
                moved += legacyIdentity to identity
            }
        }

        WorkspaceFileMutationRecoveryCoordinator(database, ops).recoverRootOrThrow("root")

        assertThat(moved).containsExactly(legacyIdentity(nonce, rawKey) to PersistentFileIdentity.encode(nonce, rawKey))
    }

    @Test
    fun `私有区改名可重入且新旧并存时拒绝合并`() {
        val base = Files.createTempDirectory("legacy-private-area").toFile().canonicalFile.toPath()
        val storage = WorkspaceInternalStorage(base, { IdentityTestDirectory(it) }, { directory, source, target, _ ->
            Files.move(directory.resolve(source), directory.resolve(target)); Unit
        })
        try {
            val legacy = legacyIdentity(nonce, rawKey)
            val current = PersistentFileIdentity.encode(nonce, rawKey)
            Files.write(storage.area(legacy).resolve("proof"), "回收站".toByteArray())

            storage.renameArea(legacy, current)
            storage.renameArea(legacy, current)

            assertThat(storage.existingArea(legacy)).isNull()
            assertThat(Files.readAllBytes(requireNotNull(storage.existingArea(current)).resolve("proof")).toString(Charsets.UTF_8))
                .isEqualTo("回收站")
            storage.area(legacy)
            assertThrows(SecurityException::class.java) { storage.renameArea(legacy, current) }
        } finally { base.toFile().deleteRecursively() }
    }

    private fun coordinator() = WorkspaceFileMutationRecoveryCoordinator(database, fileOps)

    private suspend fun rootHash(): String = requireNotNull(database.fileEntryDao().getAnyStateByUuidForLifecycle("root", "root")).hash

    private fun legacyIdentity(nonce: String, fileKey: String) = "$nonce|${Sha256Utils.hash(fileKey)}"

    private fun payload(entity: WorkspaceMutationEntity): WorkspaceMutationPayload =
        (WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload) as WorkspaceMutationPayloadResult.Valid).payload

    private suspend fun insertJournal(type: WorkspaceMutationType, source: String, target: String, rootIdentity: String) {
        val raw = WorkspaceMutationPayloadCodec.encode(
            WorkspaceMutationPayload(source, target, databaseTargetUuid = "file", expectedSha256 = rootIdentity),
        )
        database.workspaceMutationDao().insert(
            WorkspaceMutationEntity(
                operationId = "op-1",
                workspaceRootUuid = "root",
                operationType = type,
                payload = raw,
                payloadDigest = Sha256Utils.hash(raw),
                state = WorkspaceMutationStage.PREPARED,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun rootEntry(hash: String) = fileEntry("root", "/", "root", parent = null, directory = true, hash = hash)

    private fun fileEntry(
        uuid: String,
        path: String,
        name: String,
        parent: String? = "root",
        directory: Boolean = false,
        hash: String = Sha256Utils.hash("payload"),
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root",
        parentUuid = parent,
        name = name,
        hash = hash,
        isDirectory = directory,
        sizeBytes = if (directory) 0 else 7,
        physicalRootPath = root.toString(),
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
