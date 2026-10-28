package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalMountCommandAccessTest {
    private val mount = ExternalMount("mount", "docs", "real_path", "/storage/emulated/0/My docs", "session", 1)

    @Test fun `真实路径挂载提供命令路径且虚拟路径保持不变`() {
        assertThat(mount.commandAccessible).isTrue()
        val root = ExternalMountPaths.commandAccess(mount)
        assertThat(root.accessible).isTrue()
        assertThat(root.realPath).isEqualTo(mount.sourceId)
        assertThat(root.unavailableReason).isNull()
        assertThat(ExternalMountPaths.commandAccess(mount, "/子目录/a.txt").realPath)
            .isEqualTo("/storage/emulated/0/My docs/子目录/a.txt")
        assertThat(ExternalMountPaths.virtualPath(mount, "/子目录/a.txt")).isEqualTo("/mnt/docs/子目录/a.txt")
    }

    @Test fun `SAF没有命令路径且给出文件工具说明`() {
        val saf = mount.copy(sourceType = "saf_tree", sourceId = "content://test/tree/root")
        assertThat(saf.commandAccessible).isFalse()
        val access = ExternalMountPaths.commandAccess(saf, "/a.txt")
        assertThat(access.accessible).isFalse()
        assertThat(access.realPath).isNull()
        assertThat(access.unavailableReason).contains("文件工具")
    }

    @Test fun `只读真实路径挂载不向命令暴露路径`() {
        val readOnly = mount.copy(readOnly = true)
        assertThat(readOnly.commandAccessible).isFalse()
        assertThat(ExternalMountPaths.commandAccess(readOnly, "/a.txt").accessible).isFalse()
        assertThat(ExternalMountPaths.commandAccess(readOnly).realPath).isNull()
    }

    @Test fun `不可用与未知来源不返回命令路径`() {
        val unavailable = ExternalMountPaths.commandAccess(mount.copy(unavailableReason = "权限被撤销"))
        assertThat(unavailable.accessible).isFalse()
        assertThat(unavailable.unavailableReason).isEqualTo("权限被撤销")
        assertThat(ExternalMountPaths.commandAccess(mount.copy(sourceType = "unknown")).accessible).isFalse()
    }

    @Test fun `命令路径映射拒绝越界与相对来源`() {
        assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.commandAccess(mount, "/../outside") }
        assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.commandAccess(mount.copy(sourceId = "relative")) }
    }
}
