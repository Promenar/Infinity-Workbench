package com.promenar.nexara.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.linux.*
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.infra.util.Sha256Utils
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxSessionRootfsBackendTest {
    private val directory = Files.createTempDirectory("linux-strict-paths").toFile().canonicalFile
    private val rootfs = File(directory, "rootfs").apply { mkdirs() }

    @After fun cleanup() { deleteLinuxFixture(directory) }

    @Test fun `预检后最终文件被替换为guest链接时拒绝写入其它rootfs目标`() {
        val target = file("etc/value", "same")
        val secret = file("root/secret", "same")
        var replaced = false
        val metadata = object : LinuxFileMetadata by TestLinuxFileMetadata {
            override fun read(file: File): LinuxFileIdentity {
                val identity = TestLinuxFileMetadata.read(file)
                if (file == target && !replaced) {
                    replaced = true
                    Files.delete(target.toPath())
                    Files.createSymbolicLink(target.toPath(), File("/root/secret").toPath())
                }
                return identity
            }
        }
        val backend = LinuxSessionRootfsBackend(LinuxFileSystemImpl(rootfs, { true }, metadata))
        val failure = assertThrows(ExternalFolderException::class.java) {
            runBlocking { backend.write("/etc/value", "changed".toByteArray(), Sha256Utils.hash("same"), false) }
        }
        assertThat(replaced).isTrue()
        assertThat(failure.code).isEqualTo("INVALID_PATH")
        assertThat(secret.readText()).isEqualTo("same")
    }

    @Test fun `预检后祖先目录被替换为guest链接时拒绝读取其它rootfs目标`() {
        file("etc/sub/value", "public")
        file("root/value", "secret")
        val source = File(rootfs, "etc/sub")
        var replaced = false
        val metadata = object : LinuxFileMetadata by TestLinuxFileMetadata {
            override fun read(file: File): LinuxFileIdentity {
                val identity = TestLinuxFileMetadata.read(file)
                if (file == source && !replaced) {
                    replaced = true
                    Files.move(source.toPath(), File(rootfs, "etc/original").toPath())
                    Files.createSymbolicLink(source.toPath(), File("/root").toPath())
                }
                return identity
            }
        }
        val backend = LinuxSessionRootfsBackend(LinuxFileSystemImpl(rootfs, { true }, metadata))
        val failure = assertThrows(ExternalFolderException::class.java) { runBlocking { backend.read("/etc/sub/value", 1024) } }
        assertThat(replaced).isTrue()
        assertThat(failure.code).isEqualTo("INVALID_PATH")
    }

    @Test fun `严格实例拒绝所有路径操作的链接且默认界面实例保留容器链接语义`() = runBlocking {
        file("root/value", "safe")
        File(rootfs, "etc").mkdir()
        Files.createSymbolicLink(File(rootfs, "etc/link").toPath(), File("/root/value").toPath())
        Files.createSymbolicLink(File(rootfs, "etc/ancestor").toPath(), File("/root").toPath())
        val ui = LinuxFileSystemImpl(rootfs, { true }, TestLinuxFileMetadata)
        val strict = ui.forAgentTools()
        assertThat(ui.readText("/etc/link").bytes.toString(Charsets.UTF_8)).isEqualTo("safe")
        val backend = LinuxSessionRootfsBackend(ui)
        listOf<suspend () -> Unit>(
            { backend.stat("/etc/link") }, { backend.read("/etc/link", 1024) },
            { backend.write("/etc/link", byteArrayOf(), null, false) },
            { backend.createDirectory("/etc/ancestor/new") },
            { backend.move("/etc/link", "/etc/moved") }, { backend.copy("/etc/link", "/etc/copied") },
            { backend.delete("/etc/link") }, { strict.rename("/etc/link", "renamed") },
        ).forEach { action -> assertThrows(Exception::class.java) { runBlocking { action() } } }
        assertThat(File(rootfs, "root/value").readText()).isEqualTo("safe")
        assertThat(File(rootfs, "root/new").exists()).isFalse()
    }

    @Test fun `严格目录操作拒绝隐藏硬链接实体且不会修复批准目录之外的别名`() = runBlocking {
        val backing = file("root/source/nested/.l2s.data0001", "payload")
        val alias = File(rootfs, "root/alias")
        val linkTarget = File("/root/source/nested/.l2s.data0001").toPath()
        Files.createSymbolicLink(alias.toPath(), linkTarget)
        File(rootfs, "root/destination").mkdir()
        val ui = LinuxFileSystemImpl(rootfs, { true }, TestLinuxFileMetadata)
        val strict = ui.forAgentTools()
        listOf<suspend () -> Unit>(
            { strict.rename("/root/source", "renamed") },
            { strict.moveExact("/root/source", "/root/moved") },
            { strict.copyExact("/root/source", "/root/copied") },
            { strict.deleteChecked("/root/source", null) },
        ).forEach { action ->
            val failure = assertThrows(com.promenar.nexara.domain.linux.LinuxFileException::class.java) { runBlocking { action() } }
            assertThat(failure.code).isEqualTo(com.promenar.nexara.domain.linux.LinuxFileErrorCode.OUTSIDE_ROOTFS)
        }
        assertThat(strict.copy(listOf("/root/source"), "/root/destination")["/root/source"]).isEqualTo(com.promenar.nexara.domain.linux.LinuxFileErrorCode.OUTSIDE_ROOTFS)
        assertThat(strict.move(listOf("/root/source"), "/root/destination")["/root/source"]).isEqualTo(com.promenar.nexara.domain.linux.LinuxFileErrorCode.OUTSIDE_ROOTFS)
        assertThat(strict.delete(listOf("/root/source"))["/root/source"]).isEqualTo(com.promenar.nexara.domain.linux.LinuxFileErrorCode.OUTSIDE_ROOTFS)
        assertThat(backing.readText()).isEqualTo("payload")
        assertThat(Files.readSymbolicLink(alias.toPath())).isEqualTo(linkTarget)
        assertThat(File(rootfs, ".l2s").exists()).isFalse()
        assertThat(File(rootfs, "root/destination").list()).isEmpty()
        assertThat(ui.readText("/root/alias").bytes.toString(Charsets.UTF_8)).isEqualTo("payload")
    }

    @Test fun `严格路径解析每段只判断一次且永远不读取链接目标`() {
        val target = file("etc/value", "safe").toPath()
        var targetChecks = 0
        val access = mockk<LinuxFileIo> {
            every { isLink(any()) } answers {
                val path = firstArg<Path>()
                path == target && ++targetChecks > 1
            }
        }
        val paths = LinuxFilePaths(rootfs, access, rejectGuestLinks = true)
        assertThat(paths.resolve("/etc/value")).isEqualTo(target)
        assertThat(targetChecks).isEqualTo(1)
        verify(exactly = 0) { access.readLink(any()) }
    }

    private fun file(path: String, content: String) = File(rootfs, path).apply { parentFile!!.mkdirs(); writeText(content) }
}
