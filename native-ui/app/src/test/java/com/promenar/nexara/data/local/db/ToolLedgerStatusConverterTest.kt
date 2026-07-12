package com.promenar.nexara.data.local.db

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.ToolLedgerStatus
import org.junit.Test

class ToolLedgerStatusConverterTest {
    @Test
    fun allEightStatesRoundTripExactly() {
        assertThat(ToolLedgerStatus.entries).hasSize(8)
        ToolLedgerStatus.entries.forEach { state ->
            assertThat(Converters.stringToToolLedgerStatus(
                Converters.toolLedgerStatusToString(state),
            )).isEqualTo(state)
        }
    }

    @Test
    fun unknownPersistedStateIsRejectedExplicitly() {
        val failure = runCatching {
            Converters.stringToToolLedgerStatus("UNKNOWN_FROM_BACKUP")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("拒绝读取数据库")
    }
}
