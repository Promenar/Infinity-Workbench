package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ToolCall
import io.mockk.mockk
import org.junit.Test

class IrreversibleDeleteApprovalTest {
    @Test fun `两档不可恢复删除免审批但技能目录删除逐次审批`() {
        val strategy = DefaultChatGenerationContentStrategy(mockk<SharedPreferences>(relaxed = true), null)
        val call = ToolCall("permanent", "delete_file", """{"path":"/mnt/termux/big.bin","irreversible":true}""")
        listOf("workspace", "full_access", "auto", "semi", "manual").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(listOf(call), mode)).isEmpty()
            assertThat(strategy.pendingApprovalIds(listOf(call.copy(arguments = """{"path":"/skills/demo","irreversible":true}""")), mode))
                .containsExactly("permanent")
        }
    }
}
