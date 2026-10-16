package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class SessionRuntimeDataCleanupTest {
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
