package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class SessionRuntimeDataCleanupTest {
    @Test fun `其它清理失败仍停止后台任务`() = runTest {
        var stopped=false
        val error=runCatching {cleanupSessionRuntimeData({throw IOException("grants")},{},deleteJobs={stopped=true})}.exceptionOrNull()
        assertThat(stopped).isTrue()
        assertThat(error).isInstanceOf(IOException::class.java)
    }

    @Test fun `前序清理失败仍撤销定时排期和持久确认`() = runTest {
        val cleared = mutableListOf<String>()
        val failure = runCatching {
            cleanupSessionRuntimeData(
                deleteGrants = { throw IOException("grants") }, deleteOutputs = {},
                deleteSchedules = { cleared += "schedule"; throw IOException("schedule") },
                deleteTriggerInteraction = { cleared += "interaction" },
            )
        }.exceptionOrNull()!!
        assertThat(cleared).containsExactly("schedule", "interaction").inOrder()
        assertThat(failure.suppressed.single().message).isEqualTo("schedule")
    }

    @Test fun `前序清理失败仍清理画布并保留失败链`() = runTest {
        var canvasDeleted = false
        val failure = runCatching {
            cleanupSessionRuntimeData(
                deleteGrants = { throw IOException("grants") },
                deleteOutputs = {},
                deleteCanvases = { canvasDeleted = true; throw IOException("canvas") },
            )
        }.exceptionOrNull()!!
        assertThat(canvasDeleted).isTrue()
        assertThat(failure.suppressed.map { it.message }).containsExactly("canvas")
    }

    @Test
    fun `会话删除即使其它缓存清理失败仍清理折叠缓存`() = runTest {
        val attempted = mutableListOf<String>()
        val failure = runCatching {
            cleanupSessionRuntimeData(
                deleteGrants = { attempted += "grants"; throw IOException("grants failed") },
                deleteOutputs = { attempted += "outputs" },
                deleteMounts = { attempted += "mounts" },
                deleteFolds = { attempted += "folds"; throw IOException("folds failed") },
                deleteSubagents = { attempted += "subagents" },
                deleteComposerQueue = { attempted += "queue" },
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        // 子代理记录挂在父会话下，前序清理失败也要删除。
        assertThat(attempted).containsExactly("grants", "outputs", "mounts", "folds", "subagents", "queue").inOrder()
    }

    @Test
    fun `前序清理失败不能跳过会话挂载授权释放`() = runTest {
        var mountsCleaned = false
        val failure = runCatching {
            cleanupSessionRuntimeData(
                { throw IOException("grants failed") },
                { throw IOException("outputs failed") },
                { mountsCleaned = true; throw IOException("mounts failed") },
            )
        }.exceptionOrNull()!!
        assertThat(mountsCleaned).isTrue()
        assertThat(failure.suppressed.map { it.message }).containsExactly("outputs failed", "mounts failed").inOrder()
    }
    @Test
    fun `授权清理失败仍尝试输出清理并保留两个失败`() = runTest {
        val attempted = mutableListOf<String>()
        val error = runCatching {
            cleanupSessionRuntimeData(
                { attempted += "grants"; throw IOException("grants failed") },
                { attempted += "outputs"; throw IOException("outputs failed") },
            )
        }.exceptionOrNull()!!
        assertThat(attempted).containsExactly("grants", "outputs").inOrder()
        assertThat(error.message).isEqualTo("grants failed")
        assertThat(error.suppressed.single().message).isEqualTo("outputs failed")
    }
}
