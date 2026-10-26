package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PersistentRootIdentityTest {
    @Test
    fun `设备号变化不改变根身份且可以读取原文件`() {
        val root = Files.createTempDirectory("persistent-root").toFile().canonicalFile.toPath()
        var device = "1"
        val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) },
            identityFileKey = { it.toString().replace(Regex("dev=[^,)]+"), "dev=$device") }, rootNonceReader = { _, _ -> null })
        try {
            val rawKey = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()
            Files.write(root.resolve(".nexara_root_identity"), PersistentFileIdentity.marker(java.util.UUID.randomUUID().toString(), rawKey).toByteArray())
            val identity = ops.ensureRoot(root)
            WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
            Files.write(root.resolve("note.txt"), "内容".toByteArray())
            val before = ops.inspect(root, listOf("note.txt"))
            device = "123"
            assertThat(ops.ensureRoot(root, initializeIdentity = false, expectedIdentity = identity)).isEqualTo(identity)
            assertThat(ops.read(root, listOf("note.txt")).toString(Charsets.UTF_8)).isEqualTo("内容")
            assertThat(ops.inspect(root, listOf("note.txt"))).isEqualTo(before)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test
    fun `复制根标记到另一个inode必须拒绝`() {
        val base = Files.createTempDirectory("root-copy").toFile().canonicalFile.toPath()
        val root = Files.createDirectory(base.resolve("root"))
        val copy = Files.createDirectory(base.resolve("copy"))
        val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> null })
        try {
            val identity = ops.ensureRoot(root)
            Files.copy(root.resolve(".nexara_root_identity"), copy.resolve(".nexara_root_identity"))
            org.junit.Assert.assertThrows(SecurityException::class.java) { ops.ensureRoot(copy, false, identity) }
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `同一inode替换nonce必须拒绝`() {
        val root = Files.createTempDirectory("root-nonce").toFile().canonicalFile.toPath()
        val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> null })
        try {
            val identity = ops.ensureRoot(root)
            WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
            Files.write(root.resolve(".nexara_root_identity"), PersistentFileIdentity.marker(java.util.UUID.randomUUID().toString(),
                Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()).toByteArray())
            org.junit.Assert.assertThrows(SecurityException::class.java) { ops.ensureRoot(root, false, identity) }
            org.junit.Assert.assertThrows(SecurityException::class.java) { ops.listChildren(root, emptyList()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `私有索引与区域在新进程设备号变化后保持定位且可删除`() {
        val base = Files.createTempDirectory("private-stable").toFile().canonicalFile.toPath()
        fun storage() = WorkspaceInternalStorage(base, { IdentityTestDirectory(it) }, { path, source, target, _ ->
            Files.move(path.resolve(source), path.resolve(target)); Unit
        })
        try {
            val nonce = java.util.UUID.randomUUID().toString()
            val identity = PersistentFileIdentity.encode(nonce, "(dev=1,ino=11)")
            val first = storage()
            first.storeIdentity(identity, PersistentFileIdentity.marker(nonce, "(dev=1,ino=11)"))
            val area = first.area(identity)
            Files.write(area.resolve("proof"), "私有内容".toByteArray())
            val rebootedIdentity = PersistentFileIdentity.encode(nonce, "(dev=123,ino=11)")
            val rebooted = storage()
            assertThat(rebooted.readIdentity(rebootedIdentity)).isEqualTo(PersistentFileIdentity.marker(nonce, "ino=11"))
            assertThat(rebooted.existingArea(rebootedIdentity)).isEqualTo(area)
            rebooted.removeIdentity(rebootedIdentity)
            assertThat(rebooted.readIdentity(identity)).isNull()
        } finally { base.toFile().deleteRecursively() }
    }

}

internal class IdentityTestDirectory(private val root: Path) : java.nio.file.SecureDirectoryStream<Path> {
    override fun close() = Unit
    override fun iterator(): MutableIterator<Path> = Files.list(root).use { it.toList().toMutableList().iterator() }
    override fun newDirectoryStream(path: Path, vararg options: java.nio.file.LinkOption) = IdentityTestDirectory(resolve(path))
    override fun newByteChannel(path: Path, options: MutableSet<out java.nio.file.OpenOption>, vararg attrs: java.nio.file.attribute.FileAttribute<*>) =
        Files.newByteChannel(resolve(path), options, *attrs)
    override fun deleteFile(path: Path) { Files.delete(resolve(path)) }
    override fun deleteDirectory(path: Path) { Files.delete(resolve(path)) }
    override fun move(source: Path, targetDirectory: java.nio.file.SecureDirectoryStream<Path>, target: Path) {
        Files.move(resolve(source), (targetDirectory as IdentityTestDirectory).resolve(target), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    override fun <V : java.nio.file.attribute.FileAttributeView> getFileAttributeView(type: Class<V>): V =
        Files.getFileAttributeView(root, type, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    override fun <V : java.nio.file.attribute.FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: java.nio.file.LinkOption): V =
        Files.getFileAttributeView(resolve(path), type, *options)
    private fun resolve(path: Path): Path {
        require(!path.isAbsolute && path.none { it.toString() == ".." })
        var current = root
        path.forEach { part -> current = current.resolve(part); if (Files.isSymbolicLink(current)) throw SecurityException("夹具拒绝符号链接") }
        return current
    }
}
