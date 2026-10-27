package com.promenar.nexara.data.canvas

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrototypeCanvasTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `原型保存每版屏幕清单并支持编辑复制删除`() = runTest {
        val store = FileCanvasStore(folder.root)
        val first = listOf(CanvasScreenSource("one", "首页", "hello"), CanvasScreenSource("two", "详情", "detail"))
        val created = store.createPrototype("s", "原型", first, "m")
        assertThat(created.html).isEqualTo("hello")
        assertThat(created.metadata.workspacePath).isNull()
        val edited = store.updatePrototype("s", created.metadata.id, PrototypeUpdate.EditScreen("one", patches = listOf(CanvasPatch("hello", "hi")), title = "入口"), 1)
        assertThat(edited.screens.first()).isEqualTo(CanvasScreenSource("one", "入口", "hi"))
        val replacement = listOf(first[1], CanvasScreenSource("three", "新屏", "new"))
        store.updatePrototype("s", created.metadata.id, PrototypeUpdate.ReplaceAll(replacement), 2)
        assertThat(store.read("s", created.metadata.id, 1)!!.screens).isEqualTo(first)
        assertThat(FileCanvasStore(folder.root).read("s", created.metadata.id)!!.screens).isEqualTo(replacement)
        store.copySession("s", "branch", mapOf("m" to "m2"))
        assertThat(store.read("branch", created.metadata.id)!!.screens).isEqualTo(replacement)
        store.deleteSession("s")
        assertThat(store.list("s")).isEmpty()
    }

    @Test fun `类型和屏幕规则拒绝非法操作`() = runTest {
        val store = FileCanvasStore(folder.root)
        val page = store.create("s", "页面", "page", "m")
        val p = store.createPrototype("s", "原型", listOf(CanvasScreenSource("one", "一", "x")), "m")
        assertThat((runCatching { store.update("s", p.metadata.id, html = "x") }.exceptionOrNull() as CanvasException).code).isEqualTo("IS_PROTOTYPE")
        assertThat((runCatching { store.updatePrototype("s", page.metadata.id, PrototypeUpdate.ReplaceAll(emptyList())) }.exceptionOrNull() as CanvasException).code).isEqualTo("NOT_PROTOTYPE")
        assertThat((runCatching { store.updatePrototype("s", p.metadata.id, PrototypeUpdate.EditScreen("one", html = "y"), 9) }.exceptionOrNull() as CanvasException).code).isEqualTo("VERSION_CONFLICT")
        for (screens in listOf(emptyList(), listOf(CanvasScreenSource("BAD", "一", "x")), List(2) { CanvasScreenSource("one", "一", "x") }, List(13) { CanvasScreenSource("s$it", "一", "x") })) {
            assertThat((runCatching { store.createPrototype("s", "原型", screens, "m") }.exceptionOrNull() as CanvasException).code).isEqualTo("INVALID_ARGUMENTS")
        }
    }
    @Test fun `单屏与全部屏幕按UTF8字节限制`() = runTest {
        val store = FileCanvasStore(folder.root)
        val large = "中".repeat(349526)
        assertThat((runCatching { store.createPrototype("s", "原型", listOf(CanvasScreenSource("a", "一", large)), "m") }.exceptionOrNull() as CanvasException).code).isEqualTo("SOURCE_TOO_LARGE")
        val megabyte = "x".repeat(1024 * 1024)
        val screens = List(5) { CanvasScreenSource("s$it", "一", megabyte) }
        assertThat((runCatching { store.createPrototype("s", "原型", screens, "m") }.exceptionOrNull() as CanvasException).code).isEqualTo("SOURCE_TOO_LARGE")
    }

}
