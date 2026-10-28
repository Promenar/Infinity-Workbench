package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalMount
import org.junit.jupiter.api.Test

class ExternalFoldersPanelStateTest {

    @Test
    fun `真实路径入口同时要求构建常量和发行能力`() {
        assertThat(availableExternalMountSources(false, false)).containsExactly(ExternalMountSource.Saf)
        assertThat(availableExternalMountSources(false, true)).containsExactly(ExternalMountSource.Saf)
        assertThat(availableExternalMountSources(true, false)).containsExactly(ExternalMountSource.Saf)
        assertThat(availableExternalMountSources(true, true)).containsExactly(
            ExternalMountSource.Saf,
            ExternalMountSource.RealPath,
        ).inOrder()
    }

    @Test
    fun `挂载面板只展示当前会话且空会话不显示路径`() {
        val mounts = listOf(mount("current", "session-a"), mount("other", "session-b"))

        assertThat(visibleExternalMounts(mounts, "session-a").map { it.id })
            .containsExactly("current")
        assertThat(visibleExternalMounts(mounts, null).map { it.id }).isEmpty()
        assertThat(visibleExternalMounts(mounts, "").map { it.id }).isEmpty()
    }

    @Test
    fun `回退在挂载根停止且拒绝相似前缀和越界路径`() {
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/a/b")).isEqualTo("/mnt/docs/a")
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/a")).isEqualTo("/mnt/docs")
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs")).isNull()
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs-other/a")).isNull()
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/../private")).isNull()
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/a\\private")).isNull()
    }

    @Test
    fun `文本预览保留真实换行并严格识别无效UTF8和二进制`() {
        val text = "标题\n正文\t末尾\r\n"
        assertThat(decodeExternalTextPreview(text.toByteArray(), 100))
            .isEqualTo(ExternalTextPreview.Text(text))
        assertThat(decodeExternalTextPreview(byteArrayOf(0xC3.toByte(), 0x28), 100))
            .isEqualTo(ExternalTextPreview.Binary)
        assertThat(decodeExternalTextPreview(byteArrayOf(0, 65), 100))
            .isEqualTo(ExternalTextPreview.Binary)
        assertThat(decodeExternalTextPreview(byteArrayOf(1, 65), 100))
            .isEqualTo(ExternalTextPreview.Binary)
    }

    @Test
    fun `预览预算按字节限制且不会把超限数据当作完整文本`() {
        assertThat(decodeExternalTextPreview("中文".toByteArray(), 5))
            .isEqualTo(ExternalTextPreview.TooLarge)
        assertThat(decodeExternalTextPreview("中文".toByteArray(), 6))
            .isEqualTo(ExternalTextPreview.Text("中文"))
    }

    @Test
    fun `外部打开快照文件名去除目录并保留可用扩展名`() {
        assertThat(externalPreviewFileName("../../notes.txt")).isEqualTo("notes.txt")
        assertThat(externalPreviewFileName("..\\notes.txt")).isEqualTo("notes.txt")
        assertThat(externalPreviewFileName(".." )).isEqualTo("preview")
        assertThat(externalPreviewFileName("a\u0000b.pdf")).isEqualTo("a_b.pdf")
    }

    private fun mount(id: String, sessionId: String) = ExternalMount(
        id = id,
        alias = id,
        sourceType = "saf_tree",
        sourceId = "content://provider/tree/$id",
        sessionId = sessionId,
        createdAt = 0L,
    )
}
