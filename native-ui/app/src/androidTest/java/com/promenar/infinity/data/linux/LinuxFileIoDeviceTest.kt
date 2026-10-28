package com.promenar.infinity.data.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.LinuxFileErrorCode
import com.promenar.infinity.domain.linux.LinuxFileException
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Android fd、NOFOLLOW 与 renameat2 桥的设备验收；本工作包仅编译，不连接设备。 */
@RunWith(AndroidJUnit4::class)
class LinuxFileIoDeviceTest {
    private val base = Files.createTempDirectory("linux-file-io").toFile().canonicalFile
    private val access = LinuxFileIo(true)
    @After fun cleanup() { Files.walk(base.toPath()).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }

    @Test fun pinnedParentDoesNotFollowReplacementLink() {
        val parent = File(base, "parent").apply { mkdir() }
        val source = File(parent, "file").apply { writeText("inside") }
        val outside = File(base, "outside").apply { mkdir() }
        File(outside, "file").writeText("outside")
        access.file(source.toPath()) { pinned ->
            Files.move(parent.toPath(), File(base, "saved").toPath())
            Files.createSymbolicLink(parent.toPath(), outside.toPath())
            assertThat(pinned.toFile().readText()).isEqualTo("inside")
        }
        val denied = assertThrows(LinuxFileException::class.java) { access.openRead(source.toPath()).close() }
        assertThat(denied.code).isEqualTo(LinuxFileErrorCode.OUTSIDE_ROOTFS)
    }

    @Test fun noReplaceMoveKeepsBothExistingFiles() {
        val source = File(base, "source").apply { writeText("source") }
        val target = File(base, "target").apply { writeText("target") }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { access.move(source.toPath(), target.toPath()) }
        assertThat(source.readText()).isEqualTo("source")
        assertThat(target.readText()).isEqualTo("target")
    }

    @Test fun readRevisionFollowsOpenedInode() {
        val source = File(base, "file").apply { writeText("before") }
        access.openRead(source.toPath()).use { opened ->
            val before = opened.revision()
            source.writeText("after with another size")
            assertThat(opened.revision()).isNotEqualTo(before)
        }
    }
}
