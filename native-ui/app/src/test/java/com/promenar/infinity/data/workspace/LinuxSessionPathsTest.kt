package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalFolderException
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxSessionPathsTest {
    @Test fun `相对路径属于workspace且绑定根保持独立`() {
        assertThat(LinuxSessionPaths.normalize("docs/a.txt")).isEqualTo("/workspace/docs/a.txt")
        assertThat(LinuxSessionPaths.route("/workspace/a.txt", privateWorkspace = true))
            .isEqualTo(LinuxSessionPath(LinuxSessionPathKind.PRIVATE, "/a.txt"))
        assertThat(LinuxSessionPaths.route("/workspace/a.txt", privateWorkspace = false))
            .isEqualTo(LinuxSessionPath(LinuxSessionPathKind.WORKSPACE, "/a.txt"))
        assertThat(LinuxSessionPaths.route("/home/session/a.txt", privateWorkspace = false))
            .isEqualTo(LinuxSessionPath(LinuxSessionPathKind.PRIVATE, "/a.txt"))
        assertThat(LinuxSessionPaths.route("/mnt/skills/a.md", false))
            .isEqualTo(LinuxSessionPath(LinuxSessionPathKind.EXTERNAL, "/skills/a.md"))
        assertThat(LinuxSessionPaths.route("/mnt/docs/a.txt", false))
            .isEqualTo(LinuxSessionPath(LinuxSessionPathKind.EXTERNAL, "/mnt/docs/a.txt"))
        assertThat(LinuxSessionPaths.route("/etc/profile", false))
            .isEqualTo(LinuxSessionPath(LinuxSessionPathKind.ROOTFS, "/etc/profile"))
        assertThat(LinuxSessionPaths.route("/home/session-other/a", false).kind).isEqualTo(LinuxSessionPathKind.ROOTFS)
    }

    @Test fun `任何命名空间都强拒绝点片段和宿主路径写法`() {
        listOf("../etc", "/workspace/../etc", "/home/session/../../other", "/mnt/docs/./a", "C:\\host\\file", "/etc/\u0000a", "/root/.l2s.data0001").forEach { raw ->
            assertThrows(ExternalFolderException::class.java) { LinuxSessionPaths.normalize(raw) }
        }
    }

    @Test fun `外部目录准入只由模式渠道来源和实际授权决定`() {
        assertThat(externalFolderAdmission(SessionRunMode.BUILTIN, "saf_tree", false, false)).isEqualTo(ExternalFolderAdmission.ALLOWED)
        assertThat(externalFolderAdmission(SessionRunMode.LINUX, "saf_tree", true, true)).isEqualTo(ExternalFolderAdmission.REAL_PATH_ONLY)
        assertThat(externalFolderAdmission(SessionRunMode.LINUX, "real_path", true, false)).isEqualTo(ExternalFolderAdmission.NEED_ALL_FILES_ACCESS)
        assertThat(externalFolderAdmission(SessionRunMode.LINUX, "real_path", false, true)).isEqualTo(ExternalFolderAdmission.UNAVAILABLE)
        assertThat(externalFolderAdmission(SessionRunMode.LINUX, "real_path", true, true)).isEqualTo(ExternalFolderAdmission.ALLOWED)
        assertThat(externalFolderAdmission(null, "real_path", true, true)).isEqualTo(ExternalFolderAdmission.MODE_REQUIRED)
    }
}
