package com.promenar.infinity.data.linux

import com.promenar.infinity.data.exec.linuxExecSpec
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxWakeLaunchTest {
    @TempDir lateinit var root: Path
    private fun builder(): LinuxLaunchBuilder {
        val rootfs = root.resolve("alpine").toFile().apply { mkdirs() }
        return LinuxLaunchBuilder(PackagedProot(root.resolve("lib").toFile()), rootfs, root.resolve("cache").toFile(), { emptyMap() })
    }

    @Test fun `会话收件箱绑定并固定环境且空规格不绑定`() {
        val box = root.resolve("box").toFile().apply { mkdirs() }
        val builder = builder()
        val launch = builder.build(LinuxLaunchSpec(emptyList(), wakeInbox = box))
        val index = launch.arguments.indexOf("${box.absolutePath}:/run/infinity/wake")
        assertTrue(index > 0); assertEquals("-b", launch.arguments[index - 1])
        assertEquals("/run/infinity/wake", launch.environment["INFINITY_WAKE_DIR"])
        assertTrue(root.resolve("alpine/run/infinity/wake").toFile().isDirectory)
        val empty = builder.build(LinuxLaunchSpec(emptyList()))
        assertFalse(empty.arguments.any { it.endsWith(":/run/infinity/wake") })
        assertFalse(empty.environment.containsKey("INFINITY_WAKE_DIR"))
    }

    @Test fun `拒绝歧义路径及rootfs挂载点链接`() {
        val builder = builder()
        val colon = root.resolve("box:invalid").toFile().apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { builder.build(LinuxLaunchSpec(emptyList(), wakeInbox = colon)) }
        assertThrows(IllegalArgumentException::class.java) { builder.build(LinuxLaunchSpec(emptyList(), wakeInbox = File("bad\u0000path"))) }
        val box = root.resolve("box").toFile().apply { mkdirs() }
        Files.createSymbolicLink(root.resolve("alpine/run"), box.toPath())
        assertThrows(IllegalArgumentException::class.java) { builder.build(LinuxLaunchSpec(emptyList(), wakeInbox = box)) }
        assertTrue(box.list()!!.isEmpty())
    }

    @Test fun `Linux命令规格透传收件箱且维护规格默认没有`() {
        val box = root.resolve("box").toFile()
        val spec = linuxExecSpec("true", root.toFile(), "private", emptyList(), root.resolve("skills").toFile(), wakeInbox = box)
        assertEquals(box, spec.wakeInbox)
        assertNull(LinuxLaunchSpec.shell("true").wakeInbox)
    }
}
