package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxFileWorkspaceBackendTest {
    private val root = Files.createTempDirectory("linux-workspace-backend").toFile().canonicalFile
    private val home = File(root, "root").apply { mkdir() }
    private var ready = true
    private val fs = LinuxFileSystemImpl(root, { true }, TestLinuxFileMetadata)
    private val backend = LinuxFileWorkspaceBackend(fs.forHome()) { ready }
    @After fun cleanup() { deleteLinuxFixture(root) }

    @Test fun `原生移动的流式摘要读取不受64MiB整块上限约束`() = runBlocking {
        val source = File(home, "large")
        java.io.RandomAccessFile(source, "rw").use { it.setLength(80L * 1024 * 1024) }
        val bytes = backend.readStream("/large", Long.MAX_VALUE) { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
        assertThat(bytes).isEqualTo(80L * 1024 * 1024)
    }

    @Test fun `流式读取期间文件增长不能向消费者交付超预算内容`() = runBlocking {
        val source = File(home, "growing").apply { writeText("abc") }
        var delivered = 0
        val failure = runCatching {
            backend.readStream("/growing", 3) { input ->
                source.appendText("def")
                val buffer = ByteArray(64)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    delivered += count
                }
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ExternalFolderException::class.java)
        assertThat(delivered).isAtMost(3)
        assertThat((failure as ExternalFolderException).code).isEqualTo("TOO_LARGE")
    }

    @Test fun `流式旧句柄稳定时仍核验读取后的来源路径`() = runBlocking {
        val source = File(home, "source").apply { writeText("original") }
        val heldIo = io.mockk.spyk(LinuxFileIo())
        io.mockk.every { heldIo.openRead(any()) } answers {
            val channel = Files.newByteChannel(firstArg<java.nio.file.Path>(), setOf(java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            LinuxFileIo.ReadHandle(channel, { "稳定旧句柄" }, channel::close)
        }
        val metadata = object : LinuxFileMetadata by TestLinuxFileMetadata {
            override val access = heldIo
        }
        val backend = LinuxFileWorkspaceBackend(LinuxFileSystemImpl(root, { true }, metadata)) { true }
        val failure = runCatching {
            backend.readStream("/source", Long.MAX_VALUE) { input ->
                val original = input.readBytes().toString(Charsets.UTF_8)
                Files.move(source.toPath(), File(home, "held-original").toPath())
                source.writeText("replacement-with-different-size")
                original
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ExternalFolderException::class.java)
        assertThat((failure as ExternalFolderException).code).isEqualTo("CONFLICT")
        assertThat(source.readText()).isEqualTo("replacement-with-different-size")
    }

    @Test fun `工作区文件工具还原假硬链接并保护内部实体`() = runBlocking<Unit> {
        val content = File(home, ".l2s.data0001.0002").apply { writeText("content") }
        val middle = File(home, ".l2s.data0001")
        Files.createSymbolicLink(middle.toPath(), content.toPath())
        Files.createSymbolicLink(File(home, "data").toPath(), middle.toPath())
        assertThat(backend.list("/").map { it.name }).containsExactly("data")
        val before = backend.stat("/data")!!
        assertThat(before.sha256).isNotEmpty()
        backend.write("/data", "updated".toByteArray(), before.sha256)
        assertThat(backend.read("/data").toString(Charsets.UTF_8)).isEqualTo("updated")
        assertThrows(ExternalFolderException::class.java) { runBlocking { backend.read("/.l2s.data0001.0002") } }
        assertThrows(ExternalFolderException::class.java) { runBlocking { backend.delete("/.l2s.data0001") } }
    }

    @Test fun `自选目录以虚拟根读写且拒绝相邻目录和跨根链接`() = runBlocking<Unit> {
        val selected = File(root, "opt/app").apply { mkdirs() }
        val outside = File(root, "opt/other").apply { mkdir() }
        File(outside, "secret").writeText("secret")
        val backend = LinuxFileWorkspaceBackend(fs, "/opt/app") { ready }
        backend.write("/data", "chosen".toByteArray())
        assertThat(File(selected, "data").readText()).isEqualTo("chosen")
        assertThat(File(home, "data").exists()).isFalse()
        Files.createSymbolicLink(File(selected, "link").toPath(), java.nio.file.Paths.get("/opt/other"))
        assertThrows(ExternalFolderException::class.java) { runBlocking { backend.read("/link/secret") } }
        val restricted = fs.forDirectory("/opt/app")
        listOf("/root", "/opt/application", "/opt/app/../other", "/opt/app/link/secret").forEach { path ->
            val failure = assertThrows(com.promenar.infinity.domain.linux.LinuxFileException::class.java) { restricted.resolvePath(path) }
            assertThat(failure.code).isEqualTo(com.promenar.infinity.domain.linux.LinuxFileErrorCode.OUTSIDE_ROOTFS)
        }
    }

    @Test fun `二进制写入复制移动与版本冲突沿用目录工具协议`() = runBlocking<Unit> {
        val binary = byteArrayOf(0, -1, 3, 0)
        val first = backend.write("/a", binary, createOnly = true)
        backend.createDirectory("/dir")
        backend.copy("/a", "/dir/copy")
        backend.move("/dir/copy", "/dir/moved")
        assertThat(backend.read("/dir/moved")).isEqualTo(binary)
        backend.write("/a", byteArrayOf(2))
        val changed = assertThrows(ExternalFolderException::class.java) { runBlocking { backend.write("/a", binary, first.sha256) } }
        assertThat(changed.code).isEqualTo("CONFLICT")
        ready = false
        val unavailable = assertThrows(ExternalFolderException::class.java) { runBlocking { backend.list("/") } }
        assertThat(unavailable.code).isEqualTo("UNAVAILABLE")
    }

    @Test fun `工作区不能经过普通链接读取系统目录或外部假硬链接`() = runBlocking<Unit> {
        val etc = File(root, "etc").apply { mkdir() }
        File(etc, "secret").writeText("secret")
        val hidden = File(etc, ".l2s.secret0001.0001").apply { writeText("hidden") }
        Files.createSymbolicLink(File(etc, "fake").toPath(), hidden.toPath())
        Files.createSymbolicLink(File(home, "system").toPath(), File("/etc").toPath())
        assertThat(backend.list("/")).isEmpty()
        listOf("/system/secret", "/system/fake").forEach { path ->
            assertThrows(ExternalFolderException::class.java) { runBlocking { backend.read(path) } }
        }
    }
}
