package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.infra.util.Sha256Utils
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class LegacyRootMarkerTest {
    private val root = Files.createTempDirectory("legacy-marker").toFile().canonicalFile.toPath()
    private val marker = root.resolve(".nexara_root_identity")
    private val nonce = UUID.randomUUID().toString()
    private val rawKey = Files.readAttributes(root, BasicFileAttributes::class.java).fileKey().toString()
    private val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> null })

    @After fun tearDown() { root.toFile().deleteRecursively() }

    @Test fun `默认父目录的旧标记核验后改写nx1且保留原nonce`() {
        marker.toFile().writeText("$nonce\n$rawKey")

        val identity = ops.ensureRoot(root, allowUnboundParent = true)

        assertThat(identity).isEqualTo(PersistentFileIdentity.encode(nonce, rawKey))
        assertThat(marker.toFile().readText()).isEqualTo(PersistentFileIdentity.marker(nonce, rawKey))
        assertThat(ops.ensureRoot(root, false, identity, true)).isEqualTo(identity)
    }

    @Test fun `旧标记设备号变化但inode相同仍可核验旧认领`() {
        val recordedKey = rawKey.replace(Regex("dev=[^,)]+"), "dev=abcdef")
        marker.toFile().writeText("$nonce\n$recordedKey")
        val claimed = "$nonce|${Sha256Utils.hash(recordedKey)}"

        assertThat(ops.adoptLegacyRootIdentity(root, claimed)).isEqualTo(PersistentFileIdentity.encode(nonce, rawKey))
        assertThat(marker.toFile().readText()).isEqualTo("$nonce\n$recordedKey")
        assertThat(ops.ensureRoot(root, allowUnboundParent = true)).isEqualTo(PersistentFileIdentity.encode(nonce, rawKey))
    }

    @Test fun `旧标记inode或nonce无效时零写入拒绝`() {
        listOf("$nonce\n(dev=1,ino=0)", "$nonce\n(dev=1,ino=18446744073709551615)",
            "not-a-uuid\n$rawKey", "$nonce\n(dev=not-hex,ino=42)", "$nonce\nino=42").forEach { value ->
            marker.toFile().writeText(value)
            assertThrows(SecurityException::class.java) { ops.ensureRoot(root, allowUnboundParent = true) }
            assertThat(ops.adoptLegacyRootIdentity(root, "$nonce|${Sha256Utils.hash(value.substringAfter('\n'))}")).isNull()
            assertThat(marker.toFile().readText()).isEqualTo(value)
            assertThat(Files.list(root).use { it.count() }).isEqualTo(1)
        }
    }

    @Test fun `旧认领nonce或摘要与标记不符不能迁移`() {
        val value = "$nonce\n$rawKey"
        marker.toFile().writeText(value)
        assertThat(ops.adoptLegacyRootIdentity(root, "${UUID.randomUUID()}|${Sha256Utils.hash(rawKey)}")).isNull()
        assertThat(ops.adoptLegacyRootIdentity(root, "$nonce|${"a".repeat(64)}")).isNull()
        assertThat(marker.toFile().readText()).isEqualTo(value)
    }

    @Test fun `nx1标记保持原格式且不能作为旧摘要的替代证据`() {
        val value = PersistentFileIdentity.marker(nonce, rawKey)
        marker.toFile().writeText(value)
        val identity = ops.ensureRoot(root)
        assertThat(marker.toFile().readText()).isEqualTo(value)
        assertThat(ops.adoptLegacyRootIdentity(root, "$nonce|${Sha256Utils.hash(rawKey)}")).isNull()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
        root.resolve("note.txt").toFile().writeText("内容")
        assertThat(ops.read(root, listOf("note.txt")).toString(Charsets.UTF_8)).isEqualTo("内容")
    }

    @Test fun `旧标记不匹配现行认领时不改写标记`() {
        val value = "$nonce\n$rawKey"
        marker.toFile().writeText(value)
        val claimed = PersistentFileIdentity.encode(UUID.randomUUID().toString(), rawKey)
        assertThrows(SecurityException::class.java) { ops.ensureRoot(root, false, claimed) }
        assertThat(marker.toFile().readText()).isEqualTo(value)
    }

    @Test fun `目录属性nonce与旧标记冲突时不迁移旧认领`() {
        val value = "$nonce\n$rawKey"
        marker.toFile().writeText(value)
        val conflicting = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) },
            rootNonceReader = { _, _ -> UUID.randomUUID().toString() })
        assertThat(conflicting.adoptLegacyRootIdentity(root, "$nonce|${Sha256Utils.hash(rawKey)}")).isNull()
        assertThat(marker.toFile().readText()).isEqualTo(value)
    }

    @Test fun `有私有索引时旧标记迁入索引后删除`() {
        val base = Files.createTempDirectory("legacy-marker-private").toFile().canonicalFile.toPath()
        val storage = WorkspaceInternalStorage(base, { IdentityTestDirectory(it) }, { directory, source, target, _ ->
            Files.move(directory.resolve(source), directory.resolve(target)); Unit
        })
        val privateOps = SecureWorkspaceFileOps(identityStorage = storage,
            directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> nonce })
        try {
            marker.toFile().writeText("$nonce\n$rawKey")
            val identity = privateOps.ensureRoot(root, allowUnboundParent = true)
            assertThat(Files.exists(marker)).isFalse()
            assertThat(storage.readIdentity(identity)).isEqualTo(PersistentFileIdentity.marker(nonce, rawKey))
            assertThat(privateOps.ensureRoot(root, false, identity)).isEqualTo(identity)
        } finally { base.toFile().deleteRecursively() }
    }
}
