package com.promenar.nexara.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.ui.common.FolderCrumb
import com.promenar.nexara.ui.common.navigateToCrumb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WorkspaceFolderBrowserTest {
    private val docs = entry("docs", "root", "/docs", isDirectory = true)
    private val specs = entry("specs", "docs", "/docs/specs", isDirectory = true)
    private val plan = entry("plan", "specs", "/docs/specs/plan.md", isDirectory = false)
    private val byId = listOf(docs, specs, plan).associateBy(FileEntry::uuid)

    @Test
    fun `search hit resolves folder chain from workspace root`() = runTest {
        val chain = resolveFolderChain(plan, "root") { byId[it] }

        assertThat(chain).containsExactly(FolderCrumb("docs", "docs"), FolderCrumb("specs", "specs")).inOrder()
    }

    @Test
    fun `opening a file lands in its folder while opening a folder enters it`() = runTest {
        val fileHit = WorkspaceSearchHit(plan, resolveFolderChain(plan, "root") { byId[it] })
        val folderHit = WorkspaceSearchHit(specs, resolveFolderChain(specs, "root") { byId[it] })

        assertThat(fileHit.navigationStack.map(FolderCrumb::id)).containsExactly("docs", "specs").inOrder()
        assertThat(folderHit.navigationStack.map(FolderCrumb::id)).containsExactly("docs", "specs").inOrder()
        assertThat(WorkspaceSearchHit(docs, emptyList()).navigationStack.map(FolderCrumb::id)).containsExactly("docs")
    }

    @Test
    fun `chain stops on missing parent or cycle`() = runTest {
        val orphan = entry("orphan", "gone", "/gone/orphan.txt", isDirectory = false)
        assertThat(resolveFolderChain(orphan, "root") { byId[it] }).isEmpty()

        val loopA = entry("a", "b", "/a", isDirectory = true)
        val loopB = entry("b", "a", "/b", isDirectory = true)
        val loop = mapOf("a" to loopA, "b" to loopB)
        assertThat(resolveFolderChain(loopA, "root") { loop[it] }.map(FolderCrumb::id)).containsExactly("b")
    }

    @Test
    fun `breadcrumb navigation keeps the selected level`() {
        val stack = listOf(FolderCrumb("docs", "docs"), FolderCrumb("specs", "specs"), FolderCrumb("v2", "v2"))

        assertThat(stack.navigateToCrumb(0)).isEmpty()
        assertThat(stack.navigateToCrumb(2).map(FolderCrumb::id)).containsExactly("docs", "specs").inOrder()
    }

    @Test
    fun `定位路径进入最近的已存在目录`() = runTest {
        val byPath = listOf(docs, specs, plan).associateBy(FileEntry::materializedPath)
        val lookup: suspend (String) -> FileEntry? = { byPath[it] }

        assertThat(locateFolderStack("/docs/specs", "root", lookup)).isEqualTo(specs)
        assertThat(locateFolderStack("docs/specs/", "root", lookup)).isEqualTo(specs)
        // 目录不存在时向上回退；文件路径回退到所在目录。
        assertThat(locateFolderStack("/docs/specs/gone/deeper", "root", lookup)).isEqualTo(specs)
        assertThat(locateFolderStack("/docs/specs/plan.md", "root", lookup)).isEqualTo(specs)
        assertThat(locateFolderStack("/missing/x", "root", lookup)).isNull()
        assertThat(locateFolderStack("/", "root", lookup)).isNull()
    }

    @Test
    fun `资源管理器只把图片、视频、音频当作可预览媒体`() {
        assertThat(isPreviewableMediaEntry(entry("p", "root", "/a.bin", false).copy(mimeType = "image/png"))).isTrue()
        assertThat(isPreviewableMediaEntry(entry("v", "root", "/clip.MP4", false))).isTrue()
        assertThat(isPreviewableMediaEntry(entry("m", "root", "/voice.bin", false).copy(mimeType = "audio/mpeg"))).isTrue()
        assertThat(isPreviewableMediaEntry(plan)).isFalse()
        assertThat(isPreviewableMediaEntry(entry("d", "root", "/pics.png", true))).isFalse()
    }

    private fun entry(uuid: String, parentUuid: String, path: String, isDirectory: Boolean) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root",
        parentUuid = parentUuid,
        name = path.substringAfterLast('/'),
        hash = "",
        isDirectory = isDirectory,
        physicalRootPath = "/tmp$path",
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
