package com.promenar.nexara.data.local.db.recovery

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 必须在真实Android私有文件系统运行，不按provider能力跳过。 */
class AndroidRecoverySnapshotStoreTest {
    private lateinit var root: Path
    private lateinit var source: Path
    private lateinit var archive: Path
    private lateinit var database: Path

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createDirectory(context.filesDir.toPath().resolve("snapshot-audit-${UUID.randomUUID()}"))
        source = Files.createDirectory(root.resolve("source"))
        archive = root.resolve("archive")
        database = source.resolve("nexara.db")
        Files.write(database, "AAAA".toByteArray())
    }
    @After fun tearDown() { root.toFile().deleteRecursively() }

    private fun store(hook: ((SnapshotPhase) -> Unit)? = null) = RecoverySnapshotStore.forTest(
        setOf(source), 64, 2L * 1024 * 1024, AndroidSnapshotFileSystem(root), hook = hook,
    )
    private fun run(snapshot: RecoverySnapshotStore, tx: String) = snapshot.createSnapshot(tx, source, database, archive)

    @Test fun exactMaterializationCreatesIdentityUsableByWorkspaceAndRequiresReceipt() {
        val snapshot = store()
        Files.write(source.resolve(".nexara_root_identity"), "old-source-marker".toByteArray())
        val manifest = snapshot.createTreeSnapshot("tx-workspace", source, archive)
        val target = root.resolve("active/workspace")
        var receipt: String? = null
        val result = snapshot.materializeVerifiedArchive("tx-workspace", archive,
            manifest.sourceManifestSha256, target) { identity ->
            assertThat(Files.exists(target)).isFalse()
            receipt = identity
        }
        val ops = com.promenar.nexara.data.repository.SecureWorkspaceFileOps()
        assertThat(ops.ensureRoot(target, initializeIdentity = false, expectedIdentity = result.rootIdentity))
            .isEqualTo(receipt)
        assertThat(snapshot.materializeVerifiedArchive("tx-workspace", archive,
            manifest.sourceManifestSha256, target, receipt) { error("已有回执不应重建") }.rootIdentity).isEqualTo(receipt)
        assertThrows(SecurityException::class.java) {
            snapshot.materializeVerifiedArchive("tx-workspace", archive, manifest.sourceManifestSha256, target) { }
        }
        assertThat(Files.readAllBytes(source.resolve(".nexara_root_identity")).toString(Charsets.UTF_8))
            .isEqualTo("old-source-marker")
    }

    @Test fun exactSubsetCopiesOnlySelectedFilesAndRequiresPreparedIdentity() {
        Files.createDirectory(source.resolve("assets"))
        Files.write(source.resolve("assets/chosen.txt"), "chosen".toByteArray())
        Files.write(source.resolve("assets/unselected.txt"), "not-selected".toByteArray())
        val snapshot = store()
        val manifest = snapshot.createTreeSnapshot("tx-subset", source, archive)
        val target = root.resolve("active/assets")
        var receipt: String? = null
        val result = snapshot.materializeVerifiedArchive("tx-subset", archive, manifest.sourceManifestSha256,
            target, selectedRelativeFiles = setOf("assets/chosen.txt")) { receipt = it }
        assertThat(result.manifest.files.map { it.relativePath }).containsExactly("assets/chosen.txt")
        assertThat(Files.exists(target.resolve("assets/unselected.txt"))).isFalse()
        assertThat(Files.exists(target.resolve("nexara.db"))).isFalse()
        assertThrows(SecurityException::class.java) {
            snapshot.materializeVerifiedArchive("tx-subset", archive, manifest.sourceManifestSha256,
                target, selectedRelativeFiles = setOf("assets/chosen.txt")) { }
        }
        assertThat(snapshot.materializeVerifiedArchive("tx-subset", archive, manifest.sourceManifestSha256,
            target, receipt, selectedRelativeFiles = setOf("assets/chosen.txt")) { error("重试不得重新认领") }.rootIdentity)
            .isEqualTo(receipt)
    }

    @Test fun descriptorSnapshotPreservesWalJournalAndEmptyDirectories() {
        Files.write(source.resolve("nexara.db-wal"), "wal-only".toByteArray())
        Files.write(source.resolve("nexara.db-journal"), "hot-journal".toByteArray())
        Files.write(source.resolve("nexara.db-shm"), "ephemeral".toByteArray())
        Files.createDirectories(source.resolve("empty/nested"))
        val manifest = run(store(), "tx-real")
        assertThat(manifest.directories).containsExactly("empty", "empty/nested").inOrder()
        assertThat(Files.readAllBytes(archive.resolve("tx-real/files/nexara.db-wal")).toString(Charsets.UTF_8)).isEqualTo("wal-only")
        assertThat(Files.exists(archive.resolve("tx-real/files/nexara.db-journal"))).isTrue()
        assertThat(Files.exists(archive.resolve("tx-real/files/nexara.db-shm"))).isFalse()
        assertThat(Files.isDirectory(archive.resolve("tx-real/files/empty/nested"))).isTrue()
        assertThat(run(store(), "tx-real")).isEqualTo(manifest)
    }

    @Test fun trustedAnchorAliasUsesSameIdentityButNeverResolvesChildSymlinks() {
        val alias = root.resolveSibling("${root.fileName}-alias")
        Files.createSymbolicLink(alias, root)
        try {
            val fileSystem = AndroidSnapshotFileSystem(alias)
            val actualKey = fileSystem.openDirectory(root.toRealPath().resolve("source")).use { it.key }
            assertThat(fileSystem.openDirectory(alias.resolve("source")).use { it.key }).isEqualTo(actualKey)
            val snapshot = RecoverySnapshotStore.forTest(setOf(alias.resolve("source")), 64,
                2L * 1024 * 1024, fileSystem)
            snapshot.createSnapshot("tx-anchor-alias", alias.resolve("source"),
                alias.resolve("source/nexara.db"), alias.resolve("archive"))
            assertThat(Files.exists(archive.resolve("tx-anchor-alias/files/nexara.db"))).isTrue()
            Files.createSymbolicLink(root.resolve("child-alias"), source)
            assertThrows(Exception::class.java) { fileSystem.openDirectory(alias.resolve("child-alias")).close() }
        } finally { Files.deleteIfExists(alias) }
    }

    @Test fun trustedAnchorAliasRetargetIsRejected() {
        val alias = root.resolveSibling("${root.fileName}-alias")
        Files.createSymbolicLink(alias, root)
        try {
            val fileSystem = AndroidSnapshotFileSystem(alias)
            val replacement = Files.createDirectory(root.resolve("replacement"))
            Files.delete(alias)
            Files.createSymbolicLink(alias, replacement)
            assertThrows(IllegalStateException::class.java) { fileSystem.openDirectory(alias).close() }
        } finally { Files.deleteIfExists(alias) }
    }

    @Test fun sourceParentSymlinkSwapNeverCopiesOutsideContent() {
        val documents = Files.createDirectory(source.resolve("documents"))
        Files.write(documents.resolve("note.txt"), "inside".toByteArray())
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.write(outside.resolve("note.txt"), "outside-secret".toByteArray())
        val snapshot = store { phase ->
            if (phase == SnapshotPhase.SOURCE_SCANNED) {
                Files.move(documents, source.resolve("retained-documents"))
                Files.createSymbolicLink(documents, outside)
            }
        }
        assertThrows(Exception::class.java) { run(snapshot, "tx-source-swap") }
        assertThat(Files.exists(archive.resolve("tx-source-swap"))).isFalse()
        assertThat(Files.readAllBytes(outside.resolve("note.txt")).toString(Charsets.UTF_8)).isEqualTo("outside-secret")
        Files.delete(documents)
    }

    @Test fun archiveParentSymlinkSwapCannotRedirectDescriptorWrites() {
        val outside = Files.createDirectory(root.resolve("outside"))
        var swapped = false
        val snapshot = store { phase ->
            if (phase == SnapshotPhase.BEFORE_COPY_FILE && !swapped) {
                swapped = true
                Files.move(archive, root.resolve("retained-archive"))
                Files.createSymbolicLink(archive, outside)
            }
        }
        assertThrows(Exception::class.java) { run(snapshot, "tx-archive-swap") }
        assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
        assertThat(Files.exists(root.resolve("retained-archive"))).isTrue()
        Files.delete(archive)
    }

    @Test fun abaAndGrowthCannotPublishMismatchingArchive() {
        val aba = store { phase ->
            if (phase == SnapshotPhase.BEFORE_COPY_FILE) Files.write(database, "BBBB".toByteArray())
            if (phase == SnapshotPhase.COPY_CHUNK) Files.write(database, "AAAA".toByteArray())
        }
        assertThrows(SnapshotStagingRetainedException::class.java) { run(aba, "tx-aba") }
        assertThat(Files.readAllBytes(database).toString(Charsets.UTF_8)).isEqualTo("AAAA")
        Files.write(database, ByteArray(128 * 1024) { 1 })
        var added = false
        val growing = store { phase ->
            if (phase == SnapshotPhase.COPY_CHUNK && !added) {
                added = true
                Files.write(database, ByteArray(128 * 1024) { 2 }, StandardOpenOption.APPEND)
            }
        }
        assertThrows(SnapshotStagingRetainedException::class.java) { run(growing, "tx-growing") }
        assertThat(Files.exists(archive.resolve("tx-aba"))).isFalse()
        assertThat(Files.exists(archive.resolve("tx-growing"))).isFalse()
    }

    @Test fun replacedStagingAndUnknownContentAreRetained() {
        val snapshot = store { phase ->
            if (phase == SnapshotPhase.BEFORE_PUBLISH) {
                val temporary = Files.list(archive).use { paths -> paths.filter { it.fileName.toString().startsWith(".tx-stage.tmp-") }.findFirst().get() }
                Files.move(temporary, root.resolve("retained-stage"))
                Files.createDirectory(temporary)
                Files.write(temporary.resolve("keep.txt"), "important".toByteArray())
            }
        }
        val failure = assertThrows(SnapshotStagingRetainedException::class.java) { run(snapshot, "tx-stage") }
        assertThat(Files.readAllBytes(failure.stagingDirectory.resolve("keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(Files.exists(root.resolve("retained-stage/files/nexara.db"))).isTrue()
    }

    @Test fun publishedReplacementIsUncertainAndNeverOverwritten() {
        val snapshot = store { phase ->
            if (phase == SnapshotPhase.AFTER_RENAME) {
                Files.move(archive.resolve("tx-published"), root.resolve("retained-published"))
                Files.createDirectory(archive.resolve("tx-published"))
                Files.write(archive.resolve("tx-published/keep.txt"), "important".toByteArray())
            }
        }
        assertThrows(SnapshotPublicationUncertainException::class.java) { run(snapshot, "tx-published") }
        assertThat(Files.readAllBytes(archive.resolve("tx-published/keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
    }

    @Test fun postRenameDurabilityFailureCanResumeSameTransaction() {
        val failing = store { phase -> if (phase == SnapshotPhase.BEFORE_ARCHIVE_FSYNC) throw java.io.IOException("模拟父目录fsync失败") }
        assertThrows(SnapshotPublicationUncertainException::class.java) { run(failing, "tx-fsync") }
        assertThat(Files.exists(archive.resolve("tx-fsync/manifest.json"))).isTrue()
        assertThat(run(store(), "tx-fsync").transactionId).isEqualTo("tx-fsync")
    }
}
