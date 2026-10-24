package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.termux.TermuxAvailability
import org.junit.Test

class SettingsVisualPresentationTest {
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
