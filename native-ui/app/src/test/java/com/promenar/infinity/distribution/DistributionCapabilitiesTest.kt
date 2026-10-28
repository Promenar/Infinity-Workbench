package com.promenar.infinity.distribution

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.BuildConfig
import org.junit.jupiter.api.Test

class DistributionCapabilitiesTest {
    @Test
    fun `所有文件访问能力仅在侧载制品可用且诊断显示渠道`() {
        assertThat(DistributionCapabilities.channel).isIn(listOf("play", "sideload"))
        assertThat(DistributionCapabilities.allFilesAccessAvailable)
            .isEqualTo(BuildConfig.FLAVOR == "sideload")
        assertThat(DistributionCapabilities.diagnosticFields()["distribution"]).isEqualTo(BuildConfig.FLAVOR)
        assertThat(DistributionCapabilities.diagnosticFields()["allFilesAccessAvailable"])
            .isEqualTo(BuildConfig.FLAVOR == "sideload")
    }
}
