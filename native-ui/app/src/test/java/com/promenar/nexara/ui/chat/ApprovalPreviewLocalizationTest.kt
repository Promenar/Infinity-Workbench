package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ApprovalPreviewLocalizationTest {
    @Test
    fun `完整永久删除提示按动作本地化`() {
        val detail = ApprovalPreview.IRREVERSIBLE_WARNING
        val preview = ApprovalPreview.Preview(ApprovalPreview.Action.DELETE, "/notes/a.md", detail)

        assertThat(localize(preview, detail)).isEqualTo("Irreversible")
        assertThat(preview.target).isEqualTo("/notes/a.md")
        assertThat(preview.detail).isEqualTo(ApprovalPreview.IRREVERSIBLE_WARNING)
    }

    @Test
    fun `网络标签本地化后脚本正文逐字保留`() {
        val code = "const label = \"可联网\";\nreturn label;"
        val detail = ApprovalPreview.NETWORK_ALLOWED + "\n" + code
        val preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", detail, scriptAllowsNetwork = true)

        assertThat(localize(preview, detail)).isEqualTo("Network allowed\n" + code)
        assertThat(preview.detail).isEqualTo(detail)
    }

    @Test
    fun `只有网络标签时按动作本地化`() {
        val preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", ApprovalPreview.NETWORK_ALLOWED, scriptAllowsNetwork = true)

        assertThat(localize(preview, ApprovalPreview.NETWORK_ALLOWED)).isEqualTo("Network allowed")
    }

    @Test
    fun `动态正文中的相同文字与不同动作均保持原文`() {
        val details = listOf(
            "const label = \"可联网\";",
            "// " + ApprovalPreview.IRREVERSIBLE_WARNING,
            ApprovalPreview.NETWORK_ALLOWED + "suffix",
        )
        details.forEach { detail ->
            val preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", detail)
            assertThat(localize(preview, detail)).isEqualTo(detail)
        }
        val filePreview = ApprovalPreview.Preview(ApprovalPreview.Action.OVERWRITE, "/notes/a.md", ApprovalPreview.IRREVERSIBLE_WARNING)
        assertThat(localize(filePreview, ApprovalPreview.IRREVERSIBLE_WARNING)).isEqualTo(ApprovalPreview.IRREVERSIBLE_WARNING)
    }

    @Test
    fun `未联网脚本以标签原文开头时正文不被本地化或移除`() {
        val code = "可联网\nreturn 1;"
        val preview = ApprovalPreview.format("exec_js", buildJsonObject {
            put("allow_network", false)
            put("code", code)
        })!!
        assertThat(localize(preview, preview.detail!!)).isEqualTo(code)
    }

    @Test
    fun `联网标签只来自参数且保留脚本自身的相同首行`() {
        val code = "可联网\nreturn 1;"
        val preview = ApprovalPreview.format("exec_js", buildJsonObject {
            put("allow_network", true)
            put("code", code)
        })!!
        assertThat(preview.scriptAllowsNetwork).isTrue()
        assertThat(localize(preview, preview.detail!!)).isEqualTo("Network allowed\n" + code)
    }

    private fun localize(preview: ApprovalPreview.Preview, detail: String): String =
        localizeApprovalPreviewDetail(preview, detail, "Irreversible", "Network allowed")
}
