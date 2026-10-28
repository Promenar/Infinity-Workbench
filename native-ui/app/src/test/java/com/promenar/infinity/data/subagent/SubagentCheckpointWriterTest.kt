package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentCheckpointWriterTest {
    @Test fun `同文件写者串行且慢写期间的待写快照合并为最新`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<String?>()
        val writer = SubagentCheckpointWriter(backgroundScope) { batch ->
            written += batch.runs.single().result
            if (written.size == 1) gate.await()
        }
        val first = writer.enqueue(batch("第一版")); first.start(); runCurrent()
        val middle = writer.enqueue(batch("中间版")); middle.start()
        val latest = writer.enqueue(batch("最新版")); latest.start(); runCurrent()
        assertThat(written).containsExactly("第一版")
        assertThat(middle.receipt.isCompleted).isFalse()
        gate.complete(Unit); runCurrent()
        first.receipt.await(); middle.receipt.await(); latest.receipt.await()
        assertThat(written).containsExactly("第一版", "最新版").inOrder()
    }

    @Test fun `不同批次的慢写互不阻塞且写入失败不会卡住后续版本`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writer = SubagentCheckpointWriter(backgroundScope) { batch ->
            if (batch.stepId == "slow") gate.await()
            if (batch.runs.single().result == "失败") error("磁盘不可用")
        }
        val slow = writer.enqueue(batch("慢写").copy(stepId = "slow")); slow.start(); runCurrent()
        val other = writer.enqueue(batch("另一个批次")); other.start(); runCurrent()
        assertThat(other.receipt.isCompleted).isTrue()
        assertThat(slow.receipt.isCompleted).isFalse()
        val failed = writer.enqueue(batch("失败")); failed.start(); runCurrent()
        assertThat(runCatching { failed.receipt.await() }.isFailure).isTrue()
        val retry = writer.enqueue(batch("重试成功")); retry.start(); runCurrent()
        retry.receipt.await()
        gate.complete(Unit); runCurrent(); slow.receipt.await()
    }

    private fun batch(result: String) = SubagentBatch("parent", "step", listOf(SubagentRun(
        index = 0, id = "sa_000001", description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.INHERIT,
        modelId = "model", result = result, maxSteps = 0, startedAt = 0,
    )))
}
