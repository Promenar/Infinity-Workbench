package com.promenar.nexara.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidDualDatabaseSourceContractTest {
    @Test
    fun `retained public v17 beside verified current v18 is normal post promotion state`() {
        assertThat(requiresDualDatabaseRecovery(source(18, CURRENT_V18_IDENTITY))).isFalse()
    }

    @Test
    fun `public v2 and internal v5 still require explicit merge`() {
        assertThat(requiresDualDatabaseRecovery(source(2, PUBLIC_V2_IDENTITY))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(5, INTERNAL_V5_IDENTITY))).isTrue()
    }

    private fun source(version: Int, identity: String): RecoveryDatabaseSource {
        val files = listOf(RecoveryFileFingerprint(CURRENT_DATABASE_NAME, 1, "a".repeat(64)))
        return RecoveryDatabaseSource("current", CURRENT_DATABASE_NAME, version, identity, files, recoveryManifestSha256(files))
    }
}
