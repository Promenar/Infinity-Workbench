package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import org.junit.Test

class ChatProviderRouteGateContractTest {
    @Test
    fun `路由准备失败字符串化不得泄漏模型与provider标识`() {
        val result = ChatRoutePreparation.Failure(
            ProviderResolution.Failure(
                ProviderResolutionError.MODEL_NOT_FOUND,
                "private-model-marker",
                "private-provider-marker",
            ),
        )

        assertThat(result.toString()).doesNotContain("private-model-marker")
        assertThat(result.toString()).doesNotContain("private-provider-marker")
        assertThat(result.toString()).contains("MODEL_NOT_FOUND")
    }
}
