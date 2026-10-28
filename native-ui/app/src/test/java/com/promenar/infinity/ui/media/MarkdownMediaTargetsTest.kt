package com.promenar.infinity.ui.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MarkdownMediaTargetsTest {
    private val legacyRoots = listOf("/data/user/0/com.promenar.infinity/files/generated_images")

    private fun image(target: String) = classifyMarkdownImageTarget(target, legacyRoots)

    @Test
    fun `https 与内嵌图片保持原有加载`() {
        assertThat(image("https://example.com/a.png")).isEqualTo(MarkdownImageTarget.Remote("https://example.com/a.png"))
        assertThat(image("HTTPS://example.com/a.png")).isInstanceOf(MarkdownImageTarget.Remote::class.java)
        assertThat(image("data:image/png;base64,AAAA")).isInstanceOf(MarkdownImageTarget.Remote::class.java)
    }

    @Test
    fun `工作区虚拟路径与相对路径交给解析器`() {
        assertThat(image("/media/2026-10-20/gen-1432-a1b2c3.png"))
            .isEqualTo(MarkdownImageTarget.Workspace("/media/2026-10-20/gen-1432-a1b2c3.png"))
        assertThat(image("/mnt/相册/a.jpg")).isEqualTo(MarkdownImageTarget.Workspace("/mnt/相册/a.jpg"))
        assertThat(image("media/a b.png")).isEqualTo(MarkdownImageTarget.Workspace("media/a b.png"))
        assertThat(image("./a.png")).isEqualTo(MarkdownImageTarget.Workspace("./a.png"))
        assertThat(image("<media/a b.png>")).isEqualTo(MarkdownImageTarget.Workspace("media/a b.png"))
    }

    @Test
    fun `旧生图绝对路径保持原有加载`() {
        val legacy = "/data/user/0/com.promenar.infinity/files/generated_images/img_1.png"
        assertThat(image(legacy)).isEqualTo(MarkdownImageTarget.LegacyGenerated(legacy))
        // 目录本身、越界与前缀相似的目录不算旧生图。
        assertThat(image("/data/user/0/com.promenar.infinity/files/generated_images/")).isInstanceOf(MarkdownImageTarget.Workspace::class.java)
        assertThat(image("/data/user/0/com.promenar.infinity/files/generated_images/../secret.db"))
            .isInstanceOf(MarkdownImageTarget.Workspace::class.java)
        assertThat(image("/data/user/0/com.promenar.infinity/files/generated_images_old/a.png"))
            .isInstanceOf(MarkdownImageTarget.Workspace::class.java)
    }

    @Test
    fun `其它本机绝对路径按虚拟路径解析而不直接读取`() {
        assertThat(image("/sdcard/DCIM/a.jpg")).isEqualTo(MarkdownImageTarget.Workspace("/sdcard/DCIM/a.jpg"))
        assertThat(image("/data/data/com.promenar.infinity/databases/x.db"))
            .isEqualTo(MarkdownImageTarget.Workspace("/data/data/com.promenar.infinity/databases/x.db"))
    }

    @Test
    fun `其它 scheme 与协议相对地址不加载`() {
        listOf(
            "file:///sdcard/a.png",
            "content://media/external/images/1",
            "http://example.com/a.png",
            "ftp://example.com/a.png",
            "//example.com/a.png",
            "javascript:alert(1)",
            "#anchor",
            "",
            "   ",
        ).forEach { target ->
            assertThat(image(target)).isEqualTo(MarkdownImageTarget.Blocked)
        }
    }

    @Test
    fun `链接目标只接管工作区路径`() {
        assertThat(classifyMarkdownLinkTarget("/media/a.pdf")).isEqualTo(MarkdownLinkTarget.Workspace("/media/a.pdf"))
        assertThat(classifyMarkdownLinkTarget("docs/报告 1.md")).isEqualTo(MarkdownLinkTarget.Workspace("docs/报告 1.md"))
        listOf("#第一节", "mailto:a@example.com", "https://example.com", "http://example.com", "tel:123", "//cdn/x", "").forEach {
            assertThat(classifyMarkdownLinkTarget(it)).isEqualTo(MarkdownLinkTarget.External)
        }
    }
}
