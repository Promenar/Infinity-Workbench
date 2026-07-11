package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UnifiedLlmClientSecurityTest {
    @Test
    fun `生产类不暴露捕获完整配置的构造器`() {
        val capturesFullConfig = UnifiedLlmClient::class.java.constructors.any { constructor ->
            constructor.parameterTypes.firstOrNull() == UnifiedProviderConfig::class.java
        }

        assertThat(capturesFullConfig).isFalse()
    }
}
