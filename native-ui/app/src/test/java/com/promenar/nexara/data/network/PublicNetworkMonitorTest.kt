package com.promenar.nexara.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PublicNetworkMonitorTest {
    @Test
    fun `系统确认可访问互联网才算已连接，需网页认证或未验证算受限，没有网络算离线`() {
        assertThat(publicNetworkStatusOf(null)).isEqualTo(PublicNetworkStatus.OFFLINE)
        assertThat(publicNetworkStatusOf(NetworkCapabilitiesView(internet = false, validated = false, captivePortal = false)))
            .isEqualTo(PublicNetworkStatus.OFFLINE)
        assertThat(publicNetworkStatusOf(NetworkCapabilitiesView(internet = true, validated = true, captivePortal = false)))
            .isEqualTo(PublicNetworkStatus.CONNECTED)
        assertThat(publicNetworkStatusOf(NetworkCapabilitiesView(internet = true, validated = false, captivePortal = false)))
            .isEqualTo(PublicNetworkStatus.LIMITED)
        assertThat(publicNetworkStatusOf(NetworkCapabilitiesView(internet = true, validated = true, captivePortal = true)))
            .isEqualTo(PublicNetworkStatus.LIMITED)
    }
}
