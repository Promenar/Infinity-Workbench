package com.promenar.nexara.ui.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.SkippedSkillGitRepository
import org.junit.Test

class SkillRestoreNoticeTest {
    @Test
    fun `技能根仓库显示为 skills 根而子目录带名称`() {
        assertThat(SkippedSkillGitRepository(".").virtualPath()).isEqualTo("/skills")
        assertThat(SkippedSkillGitRepository("").virtualPath()).isEqualTo("/skills")
        assertThat(SkippedSkillGitRepository("pdf-report").virtualPath()).isEqualTo("/skills/pdf-report")
    }

    @Test
    fun `提交号截短为 12 位且缺失时为空`() {
        assertThat(SkippedSkillGitRepository("a", commit = "3f9c2a71b0d84e6f5a1c9e2d7b3f4a6c8d0e1f2a").shortCommit()).isEqualTo("3f9c2a71b0d8")
        assertThat(SkippedSkillGitRepository("a").shortCommit()).isNull()
    }
}
