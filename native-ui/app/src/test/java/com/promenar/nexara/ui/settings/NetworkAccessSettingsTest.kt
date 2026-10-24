package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkAccessSettingsTest {
    @Test
    fun `saving a valid allowlist closes the editor without an error`() {
        var stored: String? = null
        assertThat(saveLanHosts({ stored = it }, "nas.local\n127.0.0.1")).isNull()
        assertThat(stored).isEqualTo("nas.local\n127.0.0.1")
    }

    @Test
    fun `invalid input keeps the editor open with the store message or the generic hint`() {
        assertThat(saveLanHosts({ throw IllegalArgumentException("第 2 行包含端口") }, "x")).isEqualTo("第 2 行包含端口")
        assertThat(saveLanHosts({ throw IllegalArgumentException() }, "x")).isEmpty()
        assertThat(saveLanHosts({ throw IllegalArgumentException("  ") }, "x")).isEmpty()
    }
}
