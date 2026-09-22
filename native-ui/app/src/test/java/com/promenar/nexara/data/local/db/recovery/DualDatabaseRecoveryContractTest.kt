package com.promenar.nexara.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DualDatabaseRecoveryContractTest {
    @Test
    fun `legacy identifiers are stable namespaced and table sensitive`() {
        val first = RecoveryStableId.map("sessions", "id", "same")
        assertThat(RecoveryStableId.map("sessions", "id", "same")).isEqualTo(first)
        assertThat(RecoveryStableId.map("messages", "id", "same")).isNotEqualTo(first)
        assertThat(first).isNotEqualTo("same")
    }

    @Test
    fun `source manifest is order independent and content sensitive`() {
        val main = RecoveryFileFingerprint("nexara.db", 4, "a")
        val wal = RecoveryFileFingerprint("nexara.db-wal", 9, "b")
        assertThat(recoveryManifestSha256(listOf(main, wal)))
            .isEqualTo(recoveryManifestSha256(listOf(wal, main)))
        assertThat(recoveryManifestSha256(listOf(main.copy(size = 5), wal)))
            .isNotEqualTo(recoveryManifestSha256(listOf(main, wal)))
    }
}
