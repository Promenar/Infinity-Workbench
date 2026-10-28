package com.promenar.infinity.ui.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StartupIssueReasonTest {
    @Test fun `去掉开头的异常类名，只留可读原因`() {
        assertThat(startupIssueReason("WorkspaceMutationRecoveryException: 创建 ownership 维护清理失败"))
            .isEqualTo("创建 ownership 维护清理失败")
        assertThat(startupIssueReason("java.io.IOException:  磁盘已满 ")).isEqualTo("磁盘已满")
        assertThat(startupIssueReason("OutOfMemoryError: heap")).isEqualTo("heap")
    }

    @Test fun `没有类名前缀或去掉后为空时保留原文`() {
        assertThat(startupIssueReason("移动类 journal 文件布局冲突")).isEqualTo("移动类 journal 文件布局冲突")
        assertThat(startupIssueReason("原因：Exception: 嵌套")).isEqualTo("原因：Exception: 嵌套")
        assertThat(startupIssueReason("IllegalStateException:")).isEqualTo("IllegalStateException:")
    }
}
