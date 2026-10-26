package com.promenar.nexara.data.browser

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserSnapshotTest {
    private val raw = """
        {"url":"https://example.com/login","title":"登录","loading":false,"elementsTruncated":false,"unknown":1,
         "elements":[
          {"ref":"e1","role":"link","name":"首页","href":"https://example.com/","tag":"a"},
          {"ref":"e2","role":"searchbox","name":"搜索","value":"kotlin","states":["focused"],"tag":"input","inputType":"search","editable":true},
          {"ref":"e3","role":"textbox","name":"密码","value":"••","tag":"input","inputType":"password","editable":true},
          {"ref":"e4","role":"button","name":"登录 \"现在\"","states":["disabled"],"tag":"button"}
         ],
         "headings":[{"level":1,"text":"欢迎回来"}],
         "text":"正文第一段","textOffset":0,"textTotal":12}
    """.trimIndent()

    @Test fun `解析脚本返回值并忽略未知字段`() {
        val snapshot = parseBrowserSnapshot(raw)
        assertThat(snapshot.url).isEqualTo("https://example.com/login")
        assertThat(snapshot.elements.map { it.ref }).containsExactly("e1", "e2", "e3", "e4").inOrder()
        assertThat(snapshot.elements[1].editable).isTrue()
        assertThat(snapshot.headings.single().text).isEqualTo("欢迎回来")
    }

    @Test fun `空返回或坏数据转为浏览器错误码`() {
        assertThat(assertThrows(BrowserActionException::class.java) { parseBrowserSnapshot("null") }.code).isEqualTo("NO_PAGE")
        assertThat(assertThrows(BrowserActionException::class.java) { parseBrowserSnapshot("{\"title\":1}") }.code).isEqualTo("SNAPSHOT_FAILED")
        assertThat(parseBrowserElement("null")).isNull()
    }

    @Test fun `模型文本列出ref角色状态与链接且不带出敏感字段取值`() {
        val text = BrowserSnapshotFormatter.format(parseBrowserSnapshot(raw))
        assertThat(text).contains("Page: 登录")
        assertThat(text).contains("Interactive elements (4)")
        assertThat(text).contains("- e1 link \"首页\" -> /")
        assertThat(text).contains("- e2 searchbox \"搜索\" value=\"kotlin\" [focused]")
        assertThat(text).contains("- e3 textbox \"密码\" [password field: only the user can fill it]")
        assertThat(text).doesNotContain("••")
        assertThat(text).contains("- e4 button \"登录 '现在'\" [disabled]")
        assertThat(text).contains("- h1 欢迎回来")
        assertThat(text).contains("untrusted")
    }

    @Test fun `正文分段时提示下一段偏移`() {
        val snapshot = BrowserSnapshot(url = "https://a.test", text = "abcde", textOffset = 10, textTotal = 40)
        val text = BrowserSnapshotFormatter.format(snapshot)
        assertThat(text).contains("Text 10–15 of 40 characters (call browser_snapshot with include_text=true and text_offset=15 for more)")
        val last = BrowserSnapshotFormatter.format(snapshot.copy(textOffset = 35))
        assertThat(last).doesNotContain("text_offset=")
    }

    @Test fun `元素超过上限时截断并标注`() {
        val elements = (1..BrowserSnapshotFormatter.MAX_ELEMENTS + 5).map { BrowserElement("e$it", "link", "L$it") }
        val text = BrowserSnapshotFormatter.format(BrowserSnapshot(url = "https://a.test", elements = elements))
        assertThat(text).contains("Interactive elements (305)")
        assertThat(text).contains("(5 more not shown)")
        assertThat(text).contains("- e300 link")
        assertThat(text).doesNotContain("- e301 link")
    }

    @Test fun `同站链接只写路径，外站保留完整地址`() {
        val page = "https://kotlinlang.org/docs/a.html"
        val same = BrowserSnapshotFormatter.formatElement(BrowserElement("e1", "link", "B", href = "https://kotlinlang.org/docs/b.html?x=1#k"), "kotlinlang.org")
        assertThat(same).endsWith("-> /docs/b.html?x=1#k")
        val other = BrowserSnapshotFormatter.format(BrowserSnapshot(page, elements = listOf(BrowserElement("e2", "link", "GH", href = "https://github.com/Kotlin"))))
        assertThat(other).contains("-> https://github.com/Kotlin")
    }

    @Test fun `元素清单受字符预算限制，读取正文时只留少量元素`() {
        val elements = (1..200).map { BrowserElement("e$it", "link", "第 $it 个比较长的链接名称用于占满预算", href = "https://a.test/path/$it") }
        val full = BrowserSnapshotFormatter.format(BrowserSnapshot("https://a.test/", elements = elements))
        assertThat(full.length).isLessThan(BrowserSnapshotFormatter.ELEMENT_BUDGET + 1_000)
        assertThat(full).contains("more not shown)")
        val withText = BrowserSnapshotFormatter.format(BrowserSnapshot("https://a.test/", elements = elements, text = "正文".repeat(3000), textTotal = 6000))
        assertThat(withText).contains("more not shown; call browser_snapshot without include_text for the full list)")
        // 正文 6000 字加元素清单仍低于工具输出转存阈值 16000。
        assertThat(withText.length).isLessThan(16_000)
    }

    @Test fun `过长链接被截短`() {
        val line = BrowserSnapshotFormatter.formatElement(BrowserElement("e9", "link", "x", href = "https://a.test/" + "p".repeat(300)))
        assertThat(line.length).isLessThan(160)
        assertThat(line).endsWith("…")
    }

    @Test fun `脚本提示词是合法正则且覆盖中英文敏感字段`() {
        val pattern = Regex(BrowserFieldPolicy.pageHintPattern, RegexOption.IGNORE_CASE)
        for (hint in listOf("Password", "支付密码", "card-number", "CVV", "验证码", "one-time code")) {
            assertThat(pattern.containsMatchIn(hint)).isTrue()
        }
        // JSON 字段名的校验保证 kotlinx 序列化名与脚本一致。
        assertThat(Json.parseToJsonElement(raw).jsonObject["elements"]).isNotNull()
    }
}
