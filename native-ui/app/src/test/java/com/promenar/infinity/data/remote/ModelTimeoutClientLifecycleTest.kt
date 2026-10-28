package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ModelTimeoutClientLifecycleTest {
    @Test
    fun `无插件注入客户端关闭时派生客户端也释放且不关闭共享引擎`() = runBlocking {
        val engine = MockEngine { respondOk("ok") }
        val owner = HttpClient(engine)
        val configured = owner.withModelTimeoutSupport()
        try {
            owner.close()
            owner.coroutineContext.job.join()
            assertThat(configured.coroutineContext.job.isCompleted).isTrue()
            assertThat(engine.coroutineContext.job.isActive).isTrue()
        } finally {
            configured.close()
            owner.close()
            engine.close()
        }
    }
}
