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
