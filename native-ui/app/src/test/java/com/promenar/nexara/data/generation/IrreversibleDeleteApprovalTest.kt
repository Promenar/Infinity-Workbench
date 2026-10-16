package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ToolCall
import io.mockk.mockk
import org.junit.Test

class IrreversibleDeleteApprovalTest {
    @Test fun `全自动模式也必须对不可恢复删除逐次审批`() {
        val strategy = DefaultChatGenerationContentStrategy(mockk<SharedPreferences>(relaxed = true), null)
        val call = ToolCall("permanent", "delete_file", """{"path":"/mnt/termux/big.bin","irreversible":true}""")
        assertThat(strategy.pendingApprovalIds(listOf(call), "auto")).containsExactly("permanent")
        assertThat(strategy.pendingApprovalIds(listOf(call.copy(arguments = """{"path":"/mnt/termux/a.txt"}""")), "auto")).isEmpty()
    }
}
