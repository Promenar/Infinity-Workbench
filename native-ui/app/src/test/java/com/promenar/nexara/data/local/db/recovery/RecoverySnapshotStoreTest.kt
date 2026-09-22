package com.promenar.nexara.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator

class RecoverySnapshotStoreTest {
    private lateinit var root: Path
    private lateinit var sourceRoot: Path
    private lateinit var archiveRoot: Path
    private lateinit var databaseFile: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nexara-recovery-snapshot-test")
        sourceRoot = Files.createDirectories(root.resolve("source"))
        archiveRoot = root.resolve("archive")
        databaseFile = sourceRoot.resolve("nexara.db")
        write(databaseFile, "main-db")
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    @Test
    fun `精确子集仅复制选中文件与必要父链而不泄漏其它归档内容`() {
        write(sourceRoot.resolve("documents/selected.txt"), "selected")
        write(sourceRoot.resolve("documents/unselected.txt"), "unselected")
        Files.createDirectories(sourceRoot.resolve("unselected-empty"))
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-subset", sourceRoot, archiveRoot)
        val target = root.resolve("active/assets")
        val result = snapshot.materializeVerifiedArchive("tx-subset", archiveRoot, manifest.sourceManifestSha256,
            target, selectedRelativeFiles = setOf("documents/selected.txt")) { }
        assertThat(result.manifest.files.map { it.relativePath }).containsExactly("documents/selected.txt")
        assertThat(result.manifest.directories).containsExactly("documents")
        assertThat(Files.exists(target.resolve("documents/unselected.txt"))).isFalse()
        assertThat(Files.exists(target.resolve("nexara.db"))).isFalse()
        assertThat(Files.exists(target.resolve("unselected-empty"))).isFalse()
        assertThat(Files.readAllBytes(target.resolve("documents/selected.txt")).toString(Charsets.UTF_8)).isEqualTo("selected")
    }

    @Test
    fun `子集选择拒绝缺失目录越界和可归一歧义而不创建目标`() {
        Files.createDirectories(sourceRoot.resolve("documents"))
        write(sourceRoot.resolve(".nexara_root_identity"), "old")
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-subset", sourceRoot, archiveRoot)
        val target = root.resolve("active/assets")
        listOf(setOf("missing"), setOf("documents"), setOf("../nexara.db"), setOf("/nexara.db"),
            setOf("nexara.db", "documents/../nexara.db"), setOf(".nexara_root_identity")).forEach { selection ->
            assertThrows(IllegalArgumentException::class.java) {
                snapshot.materializeVerifiedArchive("tx-subset", archiveRoot, manifest.sourceManifestSha256,
                    target, selectedRelativeFiles = selection) { error("无效选择不得进入prepared阶段") }
            }
        }
        assertThat(Files.exists(target)).isFalse()
    }

    @Test
    fun `子集已有目标缺回执或改变选择均不得接管`() {
        write(sourceRoot.resolve("selected.txt"), "selected")
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-subset", sourceRoot, archiveRoot)
        val target = root.resolve("active/assets")
        var receipt: String? = null
        snapshot.materializeVerifiedArchive("tx-subset", archiveRoot, manifest.sourceManifestSha256,
            target, selectedRelativeFiles = setOf("selected.txt")) { receipt = it }
        assertThrows(SecurityException::class.java) {
            snapshot.materializeVerifiedArchive("tx-subset", archiveRoot, manifest.sourceManifestSha256,
                target, selectedRelativeFiles = setOf("selected.txt")) { }
        }
        assertThrows(IllegalStateException::class.java) {
            snapshot.materializeVerifiedArchive("tx-subset", archiveRoot, manifest.sourceManifestSha256,
                target, receipt, selectedRelativeFiles = setOf("nexara.db")) { }
        }
        assertThat(Files.readAllBytes(target.resolve("selected.txt")).toString(Charsets.UTF_8)).isEqualTo("selected")
        assertThat(Files.exists(target.resolve("nexara.db"))).isFalse()
    }

    @Test
    fun `精确工作区物化先持久身份回执再发布且保留原归档marker`() {
        write(sourceRoot.resolve(".nexara_root_identity"), "old-marker")
        Files.createDirectories(sourceRoot.resolve("empty/nested"))
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-exact", sourceRoot, archiveRoot)
        val target = root.resolve("active/workspace")
        var receipt: String? = null
        val result = snapshot.materializeVerifiedArchive("tx-exact", archiveRoot, manifest.sourceManifestSha256, target) {
            assertThat(Files.exists(target)).isFalse()
            receipt = it
        }
        assertThat(result.rootIdentity).isEqualTo(receipt)
        assertThat(Files.exists(target.resolve("nexara.db"))).isTrue()
        assertThat(Files.isDirectory(target.resolve("empty/nested"))).isTrue()
        assertThat(Files.exists(target.resolve("files"))).isFalse()
        assertThat(result.manifest.files.map { it.relativePath }).doesNotContain(".nexara_root_identity")
        assertThat(Files.readAllBytes(archiveRoot.resolve("tx-exact/files/.nexara_root_identity")).toString(Charsets.UTF_8))
            .isEqualTo("old-marker")
        assertThat(snapshot.materializeVerifiedArchive("tx-exact", archiveRoot, manifest.sourceManifestSha256,
            target, receipt) { error("已有认证目标不应重写回执") }).isEqualTo(result)
    }

    @Test
    fun `精确目标即使内容相同也必须持有本事务身份回执`() {
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-exact", sourceRoot, archiveRoot)
        val target = root.resolve("active/workspace")
        snapshot.materializeVerifiedArchive("tx-exact", archiveRoot, manifest.sourceManifestSha256, target) { }
        assertThrows(SecurityException::class.java) {
            snapshot.materializeVerifiedArchive("tx-exact", archiveRoot, manifest.sourceManifestSha256, target) { }
        }
        assertThat(Files.readAllBytes(target.resolve("nexara.db")).toString(Charsets.UTF_8)).isEqualTo("main-db")
    }

    @Test
    fun `精确目标发布后中断能用预写身份回执恢复且新源写入无影响`() {
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-exact", sourceRoot, archiveRoot)
        val target = root.resolve("active/workspace")
        var receipt: String? = null
        val failing = store(hook = { if (it == SnapshotPhase.AFTER_RENAME) throw java.io.IOException("模拟中断") })
        assertThrows(SnapshotPublicationUncertainException::class.java) {
            failing.materializeVerifiedArchive("tx-exact", archiveRoot, manifest.sourceManifestSha256, target) { receipt = it }
        }
        write(databaseFile, "new-source")
        val recovered = snapshot.materializeVerifiedArchive("tx-exact", archiveRoot, manifest.sourceManifestSha256,
            target, receipt) { error("不得重新发布已有目标") }
        assertThat(recovered.rootIdentity).isEqualTo(receipt)
        assertThat(Files.readAllBytes(target.resolve("nexara.db")).toString(Charsets.UTF_8)).isEqualTo("main-db")
    }

    @Test
    fun `认证归档读取不重新扫描已有新写入的source`() {
        val snapshot = store()
        val manifest = snapshot.createSnapshot("tx-authenticated", sourceRoot, databaseFile, archiveRoot)
        write(databaseFile, "new-live-data")
        write(sourceRoot.resolve("new.txt"), "new-file")
        assertThat(snapshot.readVerifiedArchive("tx-authenticated", archiveRoot,
            manifest.sourceManifestSha256, manifest.sourceIdentitySha256)).isEqualTo(manifest)
        assertThat(Files.readAllBytes(databaseFile).toString(Charsets.UTF_8)).isEqualTo("new-live-data")
    }

    @Test
    fun `认证归档读取拒绝未知内容和错误journal摘要`() {
        val snapshot = store()
        val manifest = snapshot.createSnapshot("tx-authenticated", sourceRoot, databaseFile, archiveRoot)
        assertThrows(IllegalStateException::class.java) {
            snapshot.readVerifiedArchive("tx-authenticated", archiveRoot, "0".repeat(64))
        }
        write(archiveRoot.resolve("tx-authenticated/files/unknown.txt"), "keep")
        assertThrows(IllegalStateException::class.java) {
            snapshot.readVerifiedArchive("tx-authenticated", archiveRoot, manifest.sourceManifestSha256)
        }
        assertThat(Files.readAllBytes(archiveRoot.resolve("tx-authenticated/files/unknown.txt")).toString(Charsets.UTF_8))
            .isEqualTo("keep")
    }

    @Test
    fun `WAL 和 journal 原始字节进入 manifest 且 shm 不复制`() {
        val wal = sourceRoot.resolve("nexara.db-wal")
        val journal = sourceRoot.resolve("nexara.db-journal")
        val shm = sourceRoot.resolve("nexara.db-shm")
        val document = sourceRoot.resolve("documents/note.txt")
        write(wal, "wal-only-bytes\u0000\u0001")
        write(journal, "journal-bytes")
        write(shm, "shared-memory-not-archived")
        write(document, "document-bytes")

        val manifest = store().createSnapshot(
            transactionId = "tx-wal",
            sourceRoot = sourceRoot,
            databaseFile = databaseFile,
            archiveRoot = archiveRoot,
        )

        assertThat(manifest.files.map(SnapshotFile::relativePath))
            .containsExactly(
                "documents/note.txt",
                "nexara.db",
                "nexara.db-journal",
                "nexara.db-wal",
            ).inOrder()
        assertThat(manifest.files).hasSize(4)
        assertThat(manifest.files.first { it.relativePath == "nexara.db-wal" }.size)
            .isEqualTo(Files.size(wal))
        assertThat(manifest.totalBytes)
            .isEqualTo(manifest.files.sumOf(SnapshotFile::size))

        val snapshotFiles = snapshotRoot("tx-wal").resolve("files")
        assertThat(Files.readAllBytes(snapshotFiles.resolve("nexara.db-wal")))
            .isEqualTo(Files.readAllBytes(wal))
        assertThat(Files.exists(snapshotFiles.resolve("nexara.db-journal"))).isTrue()
        assertThat(Files.exists(snapshotFiles.resolve("nexara.db-shm"))).isFalse()
        assertThat(Files.exists(snapshotRoot("tx-wal").resolve("manifest.json"))).isTrue()
    }

    @Test
    fun `纯文件树快照保留所有文件包括任意 shm`() {
        val databaseShm = write(sourceRoot.resolve("nexara.db-shm"), "database-shm")
        val userShm = write(sourceRoot.resolve("other.db-shm"), "user-shm")
        val nested = write(sourceRoot.resolve("documents/note.txt"), "document")

        val manifest = store().createTreeSnapshot(
            transactionId = "tx-tree",
            sourceRoot = sourceRoot,
            archiveRoot = archiveRoot,
        )

        assertThat(manifest.files.map(SnapshotFile::relativePath))
            .containsExactly("documents/note.txt", "nexara.db", "nexara.db-shm", "other.db-shm")
            .inOrder()
        val filesRoot = snapshotRoot("tx-tree").resolve("files")
        assertThat(Files.readAllBytes(filesRoot.resolve("nexara.db-shm")))
            .isEqualTo(Files.readAllBytes(databaseShm))
        assertThat(Files.readAllBytes(filesRoot.resolve("other.db-shm")))
            .isEqualTo(Files.readAllBytes(userShm))
        assertThat(Files.readAllBytes(filesRoot.resolve("documents/note.txt")))
            .isEqualTo(Files.readAllBytes(nested))
    }

    @Test
    fun `纯文件树快照源变动时拒绝发布且重复请求保持幂等`() {
        val document = write(sourceRoot.resolve("document.txt"), "stable")
        val first = store().createTreeSnapshot("tx-tree-idempotent", sourceRoot, archiveRoot)
        val archivedDocument = snapshotRoot("tx-tree-idempotent").resolve("files/document.txt")
        val archivedBefore = Files.readAllBytes(archivedDocument)

        val second = store().createTreeSnapshot("tx-tree-idempotent", sourceRoot, archiveRoot)

        assertThat(second).isEqualTo(first)
        assertThat(Files.readAllBytes(archivedDocument)).isEqualTo(archivedBefore)

        write(document, "changed")
        val changedFailure = assertThrows(IllegalStateException::class.java) {
            store().createTreeSnapshot("tx-tree-idempotent", sourceRoot, archiveRoot)
        }
        assertThat(changedFailure).hasMessageThat().contains("源")
        assertThat(Files.readAllBytes(archivedDocument)).isEqualTo(archivedBefore)
    }

    @Test
    fun `纯文件树快照在复制后发现源变动时不发布 manifest`() {
        val document = write(sourceRoot.resolve("document.txt"), "before")
        val snapshotStore = store(beforePublish = {
            write(document, "changed-during-tree-snapshot")
        })

        val failure = assertThrows(IllegalStateException::class.java) {
            snapshotStore.createTreeSnapshot("tx-tree-mutated", sourceRoot, archiveRoot)
        }

        assertThat(failure).hasMessageThat().contains("源")
        assertThat(Files.exists(document)).isTrue()
        assertThat(Files.exists(snapshotRoot("tx-tree-mutated").resolve("manifest.json"))).isFalse()
    }

    @Test
    fun `纯文件树快照超出字节预算时在发布前失败并保留源`() {
        val largeFile = write(sourceRoot.resolve("large.bin"), "x".repeat(8 * 1024))

        val failure = assertThrows(IllegalStateException::class.java) {
            store(maxTotalBytes = Files.size(databaseFile)).createTreeSnapshot(
                transactionId = "tx-tree-byte-limit",
                sourceRoot = sourceRoot,
                archiveRoot = archiveRoot,
            )
        }

        assertThat(failure).hasMessageThat().contains("字节")
        assertThat(Files.exists(snapshotRoot("tx-tree-byte-limit").resolve("manifest.json"))).isFalse()
        assertThat(Files.exists(largeFile)).isTrue()
    }

    @Test
    fun `显式允许根之外以及源树符号链接均拒绝且不发布 manifest`() {
        val outside = Files.createTempDirectory(root, "outside")
        val outsideFile = write(outside.resolve("outside.txt"), "outside")
        Files.createSymbolicLink(sourceRoot.resolve("escape.txt"), outsideFile)

        val symlinkFailure = assertThrows(SecurityException::class.java) {
            store().createSnapshot("tx-symlink", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(symlinkFailure).hasMessageThat().contains("符号链接")
        assertThat(Files.exists(snapshotRoot("tx-symlink").resolve("manifest.json"))).isFalse()

        val disallowedRoot = Files.createTempDirectory("nexara-disallowed-source")
        try {
            val disallowedSource = Files.createDirectories(disallowedRoot.resolve("source"))
            val disallowedDatabase = write(disallowedSource.resolve("nexara.db"), "db")
            val rootFailure = assertThrows(IllegalArgumentException::class.java) {
                store().createSnapshot("tx-outside", disallowedSource, disallowedDatabase, archiveRoot)
            }
            assertThat(rootFailure).hasMessageThat().contains("允许根")
            assertThat(Files.exists(snapshotRoot("tx-outside").resolve("manifest.json"))).isFalse()
        } finally {
            Files.walk(disallowedRoot).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    @Test
    fun `源文件在复制后发生变化时失败并保留源且不发布 manifest`() {
        val document = write(sourceRoot.resolve("document.txt"), "before")
        val snapshotStore = store(beforePublish = {
            write(document, "changed-during-snapshot")
        })

        val failure = assertThrows(IllegalStateException::class.java) {
            snapshotStore.createSnapshot("tx-mutated", sourceRoot, databaseFile, archiveRoot)
        }

        assertThat(failure).hasMessageThat().contains("源")
        assertThat(Files.exists(document)).isTrue()
        assertThat(Files.readAllBytes(document)).isEqualTo("changed-during-snapshot".toByteArray())
        assertThat(Files.exists(snapshotRoot("tx-mutated").resolve("manifest.json"))).isFalse()
    }

    @Test
    fun `同一事务重复请求仅在源与既有 manifest 一致时幂等成功`() {
        write(sourceRoot.resolve("document.txt"), "stable")
        val first = store().createSnapshot("tx-idempotent", sourceRoot, databaseFile, archiveRoot)
        val archivedDb = snapshotRoot("tx-idempotent").resolve("files/nexara.db")
        val archivedBefore = Files.readAllBytes(archivedDb)

        val second = store().createSnapshot("tx-idempotent", sourceRoot, databaseFile, archiveRoot)

        assertThat(second).isEqualTo(first)
        assertThat(Files.readAllBytes(archivedDb)).isEqualTo(archivedBefore)
        assertThat(Files.exists(snapshotRoot("tx-idempotent").resolve("manifest.json"))).isTrue()

        write(sourceRoot.resolve("document.txt"), "source-changed")
        val changedFailure = assertThrows(IllegalStateException::class.java) {
            store().createSnapshot("tx-idempotent", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(changedFailure).hasMessageThat().contains("源")
        assertThat(Files.readAllBytes(archivedDb)).isEqualTo(archivedBefore)
    }

    @Test
    fun `目标已有不同内容或无 manifest 的部分目录不得被覆盖或视为成功`() {
        val conflictingRoot = snapshotRoot("tx-conflict")
        val conflictingFile = conflictingRoot.resolve("files/nexara.db")
        write(conflictingFile, "different-target")
        write(conflictingRoot.resolve("manifest.json"), "different-manifest")

        val conflict = assertThrows(IllegalStateException::class.java) {
            store().createSnapshot("tx-conflict", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(conflict).hasMessageThat().contains("目标")
        assertThat(Files.readAllBytes(conflictingFile)).isEqualTo("different-target".toByteArray())

        val partialRoot = snapshotRoot("tx-partial")
        write(partialRoot.resolve("files/nexara.db"), "partial")
        val partial = assertThrows(IllegalStateException::class.java) {
            store().createSnapshot("tx-partial", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(partial).hasMessageThat().contains("manifest")
        assertThat(Files.exists(partialRoot.resolve("manifest.json"))).isFalse()
        assertThat(Files.readAllBytes(partialRoot.resolve("files/nexara.db")))
            .isEqualTo("partial".toByteArray())
    }

    @Test
    fun `文件数与总字节限额失败时不发布成功 manifest 且不删除源`() {
        val document = write(sourceRoot.resolve("document.txt"), "document")
        val countFailure = assertThrows(IllegalStateException::class.java) {
            store(maxFiles = 1).createSnapshot("tx-count-limit", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(countFailure).hasMessageThat().contains("文件数")
        assertThat(Files.exists(snapshotRoot("tx-count-limit").resolve("manifest.json"))).isFalse()
        assertThat(Files.exists(databaseFile)).isTrue()
        assertThat(Files.exists(document)).isTrue()

        val byteFailure = assertThrows(IllegalStateException::class.java) {
            store(maxTotalBytes = 1).createSnapshot("tx-byte-limit", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(byteFailure).hasMessageThat().contains("字节")
        assertThat(Files.exists(snapshotRoot("tx-byte-limit").resolve("manifest.json"))).isFalse()
        assertThat(Files.exists(databaseFile)).isTrue()
    }

    @Test
    fun `归档 IO 失败时不发布 manifest 且源保持可读`() {
        val archiveFile = root.resolve("archive-file")
        write(archiveFile, "not-a-directory")

        assertThrows(Exception::class.java) {
            store().createSnapshot("tx-io-failure", sourceRoot, databaseFile, archiveFile)
        }
        assertThat(Files.exists(databaseFile)).isTrue()
        assertThat(Files.exists(root.resolve("archive-file/tx-io-failure/manifest.json"))).isFalse()
    }

    private fun store(
        maxFiles: Int = 32,
        maxTotalBytes: Long = 1024 * 1024,
        beforePublish: (() -> Unit)? = null,
        hook: ((SnapshotPhase) -> Unit)? = null,
        fileSystem: SnapshotFileSystem = TestSnapshotFileSystem(),
    ): RecoverySnapshotStore = RecoverySnapshotStore.forTest(
        allowedSourceRoots = setOf(sourceRoot),
        maxFiles = maxFiles,
        maxTotalBytes = maxTotalBytes,
        fileSystem = fileSystem,
        beforePublish = beforePublish,
        hook = hook,
    )

    @Test
    fun `空目录进入清单且真实复制后重复使用成功`() {
        Files.createDirectories(sourceRoot.resolve("empty/nested"))
        val manifest = store().createTreeSnapshot("tx-empty", sourceRoot, archiveRoot)
        assertThat(manifest.directories).containsExactly("empty", "empty/nested").inOrder()
        assertThat(Files.isDirectory(snapshotRoot("tx-empty").resolve("files/empty/nested"))).isTrue()
        assertThat(store().createTreeSnapshot("tx-empty", sourceRoot, archiveRoot)).isEqualTo(manifest)
    }

    @Test
    fun `归档与任一允许源根重叠都拒绝`() {
        val secondSource = Files.createDirectory(root.resolve("second-source"))
        val snapshot = RecoverySnapshotStore.forTest(setOf(sourceRoot, secondSource), 32, 1024,
            TestSnapshotFileSystem())
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.createTreeSnapshot("tx-overlap", sourceRoot, secondSource.resolve("archive"))
        }
        assertThat(Files.list(secondSource).use { it.count() }).isEqualTo(0)
    }

    @Test
    fun `复制期间ABA即使源恢复原文也拒绝发布`() {
        write(databaseFile, "AAAA")
        val snapshot = store(hook = { phase ->
            when (phase) {
                SnapshotPhase.BEFORE_COPY_FILE -> write(databaseFile, "BBBB")
                SnapshotPhase.COPY_CHUNK -> write(databaseFile, "AAAA")
                else -> Unit
            }
        })
        assertThrows(SnapshotStagingRetainedException::class.java) {
            snapshot.createSnapshot("tx-aba", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(Files.readAllBytes(databaseFile).toString(Charsets.UTF_8)).isEqualTo("AAAA")
        assertThat(Files.exists(snapshotRoot("tx-aba"))).isFalse()
    }

    @Test
    fun `增长文件的实际复制读取不超过声明字节`() {
        val large = sourceRoot.resolve("a.bin")
        val originalSize = 128 * 1024
        Files.write(large, ByteArray(originalSize) { 1 })
        val io = TestSnapshotFileSystem()
        var appended = false
        val snapshot = store(fileSystem = io, hook = { phase ->
            if (phase == SnapshotPhase.COPY_CHUNK && !appended) {
                appended = true
                Files.write(large, ByteArray(64 * 1024) { 2 }, StandardOpenOption.APPEND)
            }
        })
        assertThrows(SnapshotStagingRetainedException::class.java) {
            snapshot.createTreeSnapshot("tx-growth", sourceRoot, archiveRoot)
        }
        // 初次扫描一次、复制一次；增长字节没有进入读取和写入预算。
        assertThat(io.bytesRead.getValue(large.toRealPath())).isAtMost(originalSize * 2L)
        assertThat(Files.exists(snapshotRoot("tx-growth"))).isFalse()
    }

    @Test
    fun `复制后归档字节篡改被发布前验证拒绝`() {
        val snapshot = store(hook = { phase ->
            if (phase == SnapshotPhase.BEFORE_PUBLISH) write(staging("tx-tamper").resolve("files/nexara.db"), "bad-data")
        })
        assertThrows(SnapshotStagingRetainedException::class.java) {
            snapshot.createSnapshot("tx-tamper", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(Files.exists(snapshotRoot("tx-tamper"))).isFalse()
        assertThat(Files.readAllBytes(databaseFile).toString(Charsets.UTF_8)).isEqualTo("main-db")
    }

    @Test
    fun `临时目录被替换时保留替代目录的未知内容`() {
        val snapshot = store(hook = { phase ->
            if (phase == SnapshotPhase.BEFORE_PUBLISH) {
                val temporary = staging("tx-replaced")
                Files.move(temporary, root.resolve("retained-staging"))
                Files.createDirectory(temporary)
                write(temporary.resolve("keep.txt"), "important")
                throw java.io.IOException("模拟替换后失败")
            }
        })
        val error = assertThrows(SnapshotStagingRetainedException::class.java) {
            snapshot.createSnapshot("tx-replaced", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(Files.readAllBytes(error.stagingDirectory.resolve("keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(Files.exists(root.resolve("retained-staging/files/nexara.db"))).isTrue()
    }

    @Test
    fun `源父目录替换为symlink不能读取外部文件`() {
        val nested = Files.createDirectory(sourceRoot.resolve("docs"))
        write(nested.resolve("note.txt"), "inside")
        val outside = Files.createDirectory(root.resolve("outside"))
        write(outside.resolve("note.txt"), "outside-secret")
        val snapshot = store(hook = { phase ->
            if (phase == SnapshotPhase.SOURCE_SCANNED) {
                Files.move(nested, sourceRoot.resolve("retained-docs"))
                Files.createSymbolicLink(nested, outside)
            }
        })
        assertThrows(Exception::class.java) {
            snapshot.createTreeSnapshot("tx-source-swap", sourceRoot, archiveRoot)
        }
        assertThat(Files.exists(snapshotRoot("tx-source-swap"))).isFalse()
        assertThat(Files.readAllBytes(outside.resolve("note.txt")).toString(Charsets.UTF_8)).isEqualTo("outside-secret")
    }

    @Test
    fun `归档复用拒绝额外根文件以及额外空目录`() {
        store().createSnapshot("tx-extras", sourceRoot, databaseFile, archiveRoot)
        val extra = write(snapshotRoot("tx-extras").resolve("unknown.txt"), "keep")
        assertThrows(IllegalStateException::class.java) { store().createSnapshot("tx-extras", sourceRoot, databaseFile, archiveRoot) }
        Files.delete(extra)
        Files.createDirectory(snapshotRoot("tx-extras").resolve("files/empty-extra"))
        assertThrows(IllegalStateException::class.java) { store().createSnapshot("tx-extras", sourceRoot, databaseFile, archiveRoot) }
        assertThat(Files.isDirectory(snapshotRoot("tx-extras").resolve("files/empty-extra"))).isTrue()
    }

    @Test
    fun `超限manifest在读取内容前拒绝`() {
        store().createSnapshot("tx-huge-manifest", sourceRoot, databaseFile, archiveRoot)
        val manifest = snapshotRoot("tx-huge-manifest").resolve("manifest.json")
        java.nio.channels.FileChannel.open(manifest, StandardOpenOption.WRITE).use {
            it.position(5L * 1024 * 1024); it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        val io = TestSnapshotFileSystem()
        assertThrows(IllegalStateException::class.java) { store(fileSystem = io).createSnapshot("tx-huge-manifest", sourceRoot, databaseFile, archiveRoot) }
        assertThat(io.bytesRead[manifest.toRealPath()] ?: 0L).isEqualTo(0L)
    }

    @Test
    fun `rename后fsync失败明确待确认且同事务可验证收敛`() {
        val io = TestSnapshotFileSystem()
        io.failForce = { it == archiveRoot.toAbsolutePath().normalize().let { path -> path.parent.toRealPath().resolve(path.fileName) } }
        val snapshot = store(fileSystem = io)
        assertThrows(SnapshotPublicationUncertainException::class.java) {
            snapshot.createSnapshot("tx-sync", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(Files.exists(snapshotRoot("tx-sync").resolve("manifest.json"))).isTrue()
        io.failForce = null
        val result = snapshot.createSnapshot("tx-sync", sourceRoot, databaseFile, archiveRoot)
        assertThat(result.transactionId).isEqualTo("tx-sync")
        assertThat(io.forcedDirectories.last()).isEqualTo(archiveRoot.toRealPath())
    }

    @Test
    fun `rename后发布目录被替换不能报告成功`() {
        val snapshot = store(hook = { phase ->
            if (phase == SnapshotPhase.AFTER_RENAME) {
                Files.move(snapshotRoot("tx-post-swap"), root.resolve("retained-published"))
                Files.createDirectory(snapshotRoot("tx-post-swap"))
                write(snapshotRoot("tx-post-swap").resolve("keep.txt"), "important")
            }
        })
        assertThrows(SnapshotPublicationUncertainException::class.java) {
            snapshot.createSnapshot("tx-post-swap", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(Files.readAllBytes(snapshotRoot("tx-post-swap").resolve("keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(Files.exists(root.resolve("retained-published/manifest.json"))).isTrue()
    }

    @Test
    fun `rename后归档根路径被替换不能报告成功`() {
        val snapshot = store(hook = { phase ->
            if (phase == SnapshotPhase.AFTER_RENAME) {
                Files.move(archiveRoot, root.resolve("retained-archive"))
                Files.createDirectory(archiveRoot)
                write(archiveRoot.resolve("keep.txt"), "important")
            }
        })
        assertThrows(SnapshotPublicationUncertainException::class.java) {
            snapshot.createSnapshot("tx-root-swap", sourceRoot, databaseFile, archiveRoot)
        }
        assertThat(Files.readAllBytes(archiveRoot.resolve("keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(Files.exists(root.resolve("retained-archive/tx-root-swap/manifest.json"))).isTrue()
    }

    private fun staging(tx: String): Path = Files.list(archiveRoot).use { entries ->
        entries.filter { it.fileName.toString().startsWith(".$tx.tmp-") }.findFirst().orElseThrow()
    }

    private fun snapshotRoot(transactionId: String): Path = archiveRoot.resolve(transactionId)

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.write(
            path,
            text.toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return path
    }
}
