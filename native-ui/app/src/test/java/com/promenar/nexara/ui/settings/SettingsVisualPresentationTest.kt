package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.termux.TermuxAvailability
import org.junit.Test

class SettingsVisualPresentationTest {
    @Test
    fun `host subtitle counts unique normalized hosts using the saved allowlist contract`() {
        assertThat(scriptLanHostCount("  NAS.local\nnas.LOCAL,192.168.1.2\n[fd00::1]  ")).isEqualTo(3)
        assertThat(scriptLanHostCount(" \n\t")).isEqualTo(0)
    }

    @Test
    fun `invalid saved allowlists require review instead of appearing empty`() {
        assertThat(scriptLanHostCount("https://nas.local/private")).isNull()
        assertThat(scriptLanHostCount((1..65).joinToString("\n") { "host$it.local" })).isNull()
        assertThat(scriptLanHostCount((1..64).joinToString("\n") { "host$it.local" })).isEqualTo(64)
    }

    @Test
    fun `entry status preserves installation version and permission precedence`() {
        assertThat(termuxEntryStatus(null)).isEqualTo(TermuxEntryStatus.Loading)
        assertThat(termuxEntryStatus(TermuxAvailability(false, null, false))).isEqualTo(TermuxEntryStatus.NotInstalled)
        assertThat(termuxEntryStatus(TermuxAvailability(true, "0.108", true))).isEqualTo(TermuxEntryStatus.UnsupportedVersion)
        assertThat(termuxEntryStatus(TermuxAvailability(true, "0.119.1", false))).isEqualTo(TermuxEntryStatus.PermissionRequired)
        assertThat(termuxEntryStatus(TermuxAvailability(true, "0.119.1", true))).isEqualTo(TermuxEntryStatus.Available)
        assertThat(termuxEntryStatus(TermuxAvailability(true, null, true))).isEqualTo(TermuxEntryStatus.UnsupportedVersion)
    }
}
