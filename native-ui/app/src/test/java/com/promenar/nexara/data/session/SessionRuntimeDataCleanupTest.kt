package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class SessionRuntimeDataCleanupTest {
    @Test
    fun `会话删除即使其它缓存清理失败仍清理折叠缓存`() = runTest {
        val attempted = mutableListOf<String>()
        val failure = runCatching {
            cleanupSessionRuntimeData(
                deleteGrants = { attempted += "grants"; throw IOException("grants failed") },
                deleteOutputs = { attempted += "outputs" },
                deleteMounts = { attempted += "mounts" },
                deleteFolds = { attempted += "folds" },
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(attempted).containsExactly("grants", "outputs", "mounts", "folds").inOrder()
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
