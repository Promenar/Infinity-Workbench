package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import org.junit.Test

class RunTabLayoutTest {
    private fun job(id: String, state: BackgroundJobState) = BackgroundJob(
        id, "s", BackgroundJobRunner.LINUX, id, state, null, 0, null, 60, BackgroundJobExitAction.WAKE, null, "job:$id",
    )

    @Test fun `状态卡大号用时按时钟格式显示`() {
        assertThat(runClockText(9_000)).isEqualTo("0:09")
        assertThat(runClockText(364_000)).isEqualTo("6:04")
        assertThat(runClockText(3_725_000)).isEqualTo("1:02:05")
        assertThat(runClockText(-5)).isEqualTo("0:00")
    }

    @Test fun `后台任务分成运行中与已结束两组，组内保持原顺序`() {
        val groups = backgroundJobGroups(
            listOf(
                job("done1", BackgroundJobState.EXITED),
                job("run1", BackgroundJobState.RUNNING),
                job("fail", BackgroundJobState.FAILED),
                job("run2", BackgroundJobState.RUNNING),
            ),
        )
        assertThat(groups.running.map { it.id }).containsExactly("run1", "run2").inOrder()
        assertThat(groups.finished.map { it.id }).containsExactly("done1", "fail").inOrder()
    }
}
