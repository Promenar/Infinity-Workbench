package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.repository.PersistentFileIdentity
import org.junit.Test

class LinuxWorkspaceLocationTest {
    private val identity = PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=123")

    @Test fun `允许的四个顶层与子目录均可编码且旧主目录兼容`() {
        listOf("/root", "/root/proj", "/home", "/home/alice/code", "/opt", "/opt/app", "/srv", "/srv/site", "/root/.hidden").forEach {
            val location = "$it#$identity"
            assertThat(LinuxWorkspaceLocation.parse(location)?.directory).isEqualTo(it)
            assertThat(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_LINUX_HOME, location)).isTrue()
            assertThat(LinuxWorkspaceLocation.workingDirectory(WorkspaceEntity("id", WorkspaceEntity.KIND_LINUX_HOME, "目录", location, 0))).isEqualTo(it)
        }
    }

    @Test fun `系统目录绑定点非规范路径与无效身份均拒绝`() {
        listOf("/", "/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/run", "/sbin", "/sys", "/tmp", "/usr", "/var", "/workspace", "/mnt", "/home/session", "/home/session/a",
            "/root/", "/root//a", "/root/./a", "/root/../opt", "root/a", "/root/a#b", "/root/a\\b", "/root/a\u0000b", "/root/a\nb", "/rooted/a").forEach {
            assertThat(LinuxWorkspaceLocation.isAllowedDirectory(it)).isFalse()
            assertThat(LinuxWorkspaceLocation.parse("$it#$identity")).isNull()
        }
        listOf("/root", "/root#", "/root#bad", "/root#$identity#extra").forEach {
            assertThat(LinuxWorkspaceLocation.parse(it)).isNull()
        }
        val longest = "/root/" + "a".repeat(4090)
        assertThat(LinuxWorkspaceLocation.isAllowedDirectory(longest)).isTrue()
        assertThat(LinuxWorkspaceLocation.isAllowedDirectory(longest + "a")).isFalse()
        assertThat(LinuxWorkspaceLocation.isAllowedDirectory("/home/session2")).isTrue()
    }

    @Test fun `登记根保留隐藏名称与空格且不放宽根下保留路径`() {
        listOf("/opt/.infinity-project", "/root/.l2s.project", "/home/code ").forEach { root ->
            assertThat(LinuxSessionPaths.normalize("a", root)).isEqualTo("$root/a")
            assertThat(LinuxSessionPaths.normalize(root, root)).isEqualTo(root)
            assertThat(LinuxSessionPaths.route("$root/a", false, root)).isEqualTo(LinuxSessionPath(LinuxSessionPathKind.WORKSPACE, "/a"))
            org.junit.Assert.assertThrows(com.promenar.infinity.domain.storage.ExternalFolderException::class.java) { LinuxSessionPaths.normalize(".l2s.hidden", root) }
            org.junit.Assert.assertThrows(com.promenar.infinity.domain.storage.ExternalFolderException::class.java) { LinuxSessionPaths.normalize(".infinity-private/a", root) }
        }
    }

    @Test fun `文件路径相对目录与私有绑定优先级一致`() {
        assertThat(LinuxSessionPaths.normalize("src/a", "/opt/app")).isEqualTo("/opt/app/src/a")
        assertThat(LinuxSessionPaths.route("/opt/app/src/a", false, "/opt/app")).isEqualTo(LinuxSessionPath(LinuxSessionPathKind.WORKSPACE, "/src/a"))
        assertThat(LinuxSessionPaths.route("/workspace/src/a", false, "/opt/app").kind).isEqualTo(LinuxSessionPathKind.WORKSPACE)
        assertThat(LinuxSessionPaths.route("/opt/application/a", false, "/opt/app").kind).isEqualTo(LinuxSessionPathKind.ROOTFS)
        assertThat(LinuxSessionPaths.route("/home/session/a", false, "/home").kind).isEqualTo(LinuxSessionPathKind.PRIVATE)
    }
}
