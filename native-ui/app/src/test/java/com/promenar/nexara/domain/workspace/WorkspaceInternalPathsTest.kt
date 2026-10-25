package com.promenar.nexara.domain.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkspaceInternalPathsTest {
    @Test fun `新旧内部路径在主工作区与挂载中使用同一保护规则`() {
        listOf("/.nexara/tombstones/id", "/.recycle_bin/id", "/nested/.NEXARA_versions/id", "/mnt/user/.recycle_bin/id")
            .forEach { assertThat(WorkspaceInternalPaths.containsReservedPath(it)).isTrue() }
    }

    @Test fun `用户隐藏文件与冲突副本保持可见`() {
        listOf("/.git/config", "/.gitignore", "/background-conflicts/job-id/result.txt", "/jobs/report.log", "/nexara.txt")
            .forEach { assertThat(WorkspaceInternalPaths.containsReservedPath(it)).isFalse() }
    }
}
