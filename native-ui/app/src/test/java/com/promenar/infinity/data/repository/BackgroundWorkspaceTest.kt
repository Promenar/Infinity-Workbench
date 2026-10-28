package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView

class BackgroundWorkspaceTest {
    private lateinit var temporary: File
    private lateinit var root: File
    private lateinit var staging: File
    private val fileOps = TestWorkspaceFileOps()
    private val workspace = BackgroundWorkspace(fileOps, ::openBackgroundTestDirectory)

    @Before fun setUp() {
        temporary = Files.createTempDirectory("infinity-background-workspace").toFile()
        root = File(temporary, "root").apply { mkdir() }
        staging = File(temporary, "run/workspace")
        fileOps.ensureRoot(root.toPath())
    }

    @After fun tearDown() { temporary.deleteRecursively() }

    private fun prepare() = workspace.prepare(root, "root", "session", "job-1", staging)
    private fun synchronize(finished: Boolean = true, interrupted: Boolean = false): String? {
        val result = workspace.synchronize(root, "root", "session", "job-1", staging, finished, interrupted)
        if (finished || interrupted) workspace.complete(root, "job-1")
        return result
    }
    private fun write(path: String, text: String, base: File = root) =
        File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun `后台命令及日志不在用户根留下内部文件`() {
        prepare()
        File(staging.parentFile, "output.log").writeText("private log")
        synchronize(finished = false)
        assertThat(File(root, "jobs/job-1.log").exists()).isFalse()
        assertThat(File(root, ".infinity_command_pending-job-1").exists()).isFalse()
        assertThat(File(staging.parentFile, "output.log").readText()).isEqualTo("private log")
    }

    @Test fun `后台运行中的结果不会合入前台且日志留在私有目录`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "background", staging)
        File(staging.parentFile, "output.log").writeText("running\n")
        synchronize(finished = false)
        assertThat(File(root, "a.txt").readText()).isEqualTo("base")
        assertThat(File(staging.parentFile, "output.log").readText()).isEqualTo("running\n")
        assertThat(File(root, "jobs").exists()).isFalse()
        assertThat(File(root, ".infinity_command_pending-job-1").exists()).isFalse()
    }

    @Test fun `后台修改合入且与前台不同路径写入共存`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "background", staging)
        write("front.txt", "foreground")
        assertThat(synchronize()).isNull()
        assertThat(File(root, "a.txt").readText()).isEqualTo("background")
        assertThat(File(root, "front.txt").readText()).isEqualTo("foreground")
        assertThat(synchronize()).isNull()
    }

    @Test fun `同路径前台编辑保留后台版本进入冲突目录`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "background", staging)
        write("a.txt", "foreground")
        assertThat(synchronize()).contains("a.txt")
        assertThat(File(root, "a.txt").readText()).isEqualTo("foreground")
        assertThat(File(root, "background-conflicts/job-1/a.txt").readText()).isEqualTo("background")
    }

    @Test fun `前台先删除的文件不会被后台重建`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "background", staging)
        File(root, "a.txt").delete()
        assertThat(synchronize()).contains("a.txt")
        assertThat(File(root, "a.txt").exists()).isFalse()
        assertThat(File(root, "background-conflicts/job-1/a.txt").readText()).isEqualTo("background")
    }

    @Test fun `后台删除目录时保留前台新增后代`() {
        write("folder/base.txt", "base")
        prepare()
        File(staging, "folder").deleteRecursively()
        write("folder/new.txt", "foreground")
        assertThat(synchronize()).contains("folder")
        assertThat(File(root, "folder/base.txt").readText()).isEqualTo("base")
        assertThat(File(root, "folder/new.txt").readText()).isEqualTo("foreground")
    }

    @Test fun `后台删除未被前台改动的目录并可重复对账`() {
        write("folder/base.txt", "base")
        prepare()
        File(staging, "folder").deleteRecursively()
        assertThat(synchronize()).isNull()
        assertThat(File(root, "folder").exists()).isFalse()
        assertThat(synchronize()).isNull()
    }

    @Test fun `前台删除祖先后后台新建后代不会重建前台目录`() {
        write("folder/base.txt", "base")
        prepare()
        write("folder/new.txt", "background", staging)
        File(root, "folder").deleteRecursively()
        assertThat(synchronize()).contains("folder/new.txt")
        assertThat(File(root, "folder").exists()).isFalse()
        assertThat(File(root, "background-conflicts/job-1/folder/new.txt").readText()).isEqualTo("background")
    }

    @Test fun `目录改文件不会删除前台新建后代`() {
        write("folder/base.txt", "base")
        prepare()
        File(staging, "folder").deleteRecursively()
        write("folder", "background", staging)
        write("folder/new.txt", "foreground")
        assertThat(synchronize()).contains("folder")
        assertThat(File(root, "folder/new.txt").readText()).isEqualTo("foreground")
        assertThat(File(root, "background-conflicts/job-1/folder").readText()).isEqualTo("background")
    }

    @Test fun `前后台同时新建同一路径时保留前台版本`() {
        prepare()
        write("new.txt", "background", staging)
        write("new.txt", "foreground")
        assertThat(synchronize()).contains("new.txt")
        assertThat(File(root, "new.txt").readText()).isEqualTo("foreground")
    }

    @Test fun `同内容同大小但前台变更不会被mtime捷径覆盖`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "jobs", staging)
        write("a.txt", "edit").setLastModified(File(staging, "a.txt").lastModified())
        assertThat(synchronize()).contains("a.txt")
        assertThat(File(root, "a.txt").readText()).isEqualTo("edit")
    }

    @Test fun `工作区同名日志是用户文件且不会被后台发布覆盖`() {
        prepare()
        val log = File(staging.parentFile, "output.log").apply { writeText("first") }
        synchronize(finished = false)
        write("jobs/job-1.log", "user edit")
        log.writeText("second")
        assertThat(synchronize()).isNull()
        assertThat(File(root, "jobs/job-1.log").readText()).isEqualTo("user edit")
        assertThat(log.readText()).isEqualTo("second")
    }

    @Test fun `崩溃恢复只发布日志并清理本任务标记`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "partial", staging)
        File(staging.parentFile, "output.log").writeText("partial log")
        synchronize(finished = false, interrupted = true)
        assertThat(File(root, "a.txt").readText()).isEqualTo("base")
        assertThat(File(staging.parentFile, "output.log").readText()).isEqualTo("partial log")
        assertThat(File(root, ".infinity_command_pending-job-1").exists()).isFalse()
    }

    @Test fun `后台符号链接不会跟随合入外部文件`() {
        prepare()
        val outside = write("outside.txt", "outside", temporary)
        Files.createSymbolicLink(File(staging, "link.txt").toPath(), outside.toPath())
        assertThat(runCatching { synchronize() }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(File(root, "link.txt").exists()).isFalse()
        assertThat(outside.readText()).isEqualTo("outside")
    }

    @Test fun `前台已经采用相同后台内容时结束对账无冲突`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "same", staging)
        write("a.txt", "same")
        assertThat(synchronize()).isNull()
        assertThat(File(root, "a.txt").readText()).isEqualTo("same")
    }

    @Test fun `发布在隔离旧文件后失败时中断恢复还原原文件`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "background", staging)
        val failing = BackgroundWorkspace(object : WorkspaceFileOps by fileOps {
            override fun move(root: java.nio.file.Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
                if (source.last().startsWith("job-write-")) throw java.io.IOException("发布中断")
                return fileOps.move(root, source, target)
            }
        }, ::openBackgroundTestDirectory)
        assertThat(runCatching {
            failing.synchronize(root, "root", "session", "job-1", staging, finished = true)
        }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        synchronize(finished = false, interrupted = true)
        assertThat(File(root, "a.txt").readText()).isEqualTo("base")
    }

    @Test fun `发布中断后前台新建目标时恢复不覆盖新目标`() {
        write("a.txt", "base")
        prepare()
        write("a.txt", "background", staging)
        val failing = BackgroundWorkspace(object : WorkspaceFileOps by fileOps {
            override fun move(root: java.nio.file.Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
                if (source.last().startsWith("job-write-")) throw java.io.IOException("发布中断")
                return fileOps.move(root, source, target)
            }
        }, ::openBackgroundTestDirectory)
        runCatching { failing.synchronize(root, "root", "session", "job-1", staging, finished = true) }
        write("a.txt", "foreground")
        synchronize(finished = false, interrupted = true)
        assertThat(File(root, "a.txt").readText()).isEqualTo("foreground")
    }
}

/** 宿主 macOS 没有 descriptor-relative 目录流；仅测试夹具按路径提供真实磁盘能力。 */
private fun openBackgroundTestDirectory(path: Path): SecureDirectoryStream<Path> = BackgroundTestDirectory(path)

private class BackgroundTestDirectory(private val directory: Path) : SecureDirectoryStream<Path> {
    init { require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) }
    override fun close() = Unit
    override fun iterator(): MutableIterator<Path> =
        Files.list(directory).use { it.toList().toMutableList().iterator() }
    override fun newDirectoryStream(path: Path, vararg options: LinkOption): SecureDirectoryStream<Path> =
        BackgroundTestDirectory(resolve(path))
    override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): java.nio.channels.SeekableByteChannel =
        Files.newByteChannel(resolve(path), options, *attrs)
    override fun deleteFile(path: Path) { Files.delete(resolve(path)) }
    override fun deleteDirectory(path: Path) { Files.delete(resolve(path)) }
    override fun move(source: Path, targetDirectory: SecureDirectoryStream<Path>, target: Path) {
        Files.move(resolve(source), (targetDirectory as BackgroundTestDirectory).resolve(target))
    }
    override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V =
        Files.getFileAttributeView(directory, type, LinkOption.NOFOLLOW_LINKS)
    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V =
        Files.getFileAttributeView(resolve(path), type, *options)
    private fun resolve(path: Path): Path {
        require(!path.isAbsolute && path.none { it.toString() == ".." })
        var current = directory
        path.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) throw SecurityException("测试目录流拒绝符号链接")
        }
        return current
    }
}
