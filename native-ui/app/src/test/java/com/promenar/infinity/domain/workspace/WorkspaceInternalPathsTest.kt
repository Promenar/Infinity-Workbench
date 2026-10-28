package com.promenar.infinity.domain.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkspaceInternalPathsTest {
    @Test fun `新旧内部路径在主工作区与挂载中使用同一保护规则`() {
        listOf("/.infinity/tombstones/id", "/.recycle_bin/id", "/nested/.INFINITY_versions/id", "/mnt/user/.recycle_bin/id")
            .forEach { assertThat(WorkspaceInternalPaths.containsReservedPath(it)).isTrue() }
    }

    @Test fun `用户隐藏文件与冲突副本保持可见`() {
        listOf("/.git/config", "/.gitignore", "/background-conflicts/job-id/result.txt", "/jobs/report.log", "/infinity.txt")
            .forEach { assertThat(WorkspaceInternalPaths.containsReservedPath(it)).isFalse() }
    }
}
