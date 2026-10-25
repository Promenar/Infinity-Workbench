package com.promenar.nexara.data.linux

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.linux.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LinuxDocumentsTest {
    private val root = Files.createTempDirectory("linux-documents").toFile().canonicalFile
    private val home = File(root, "root").apply { mkdir() }
    private val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "arm64", "test", 1, null)))
    private val runtime = object : LinuxRuntime by UnavailableLinuxRuntime(LinuxUnavailableReason.DISTRIBUTION, LinuxPaths(root, home)) {
        override val state = this@LinuxDocumentsTest.state
    }
    private val files = LinuxFileSystemImpl(root, { state.value is LinuxRuntimeState.Ready }, TestLinuxFileMetadata)
    private val provider = object : LinuxDocumentsProvider() {
        override val linuxRuntime = runtime
        override val linuxFiles = files
    }.apply {
        val base = ApplicationProvider.getApplicationContext<Context>()
        // 项目 JVM 任务没有打包 Android 资源；查询协议使用资源夹具，双语资源另由 aapt2 验证。
        val resources = mockk<Resources> { every { getString(any()) } returns "Linux home" }
        val fixtureContext = object : ContextWrapper(base) { override fun getResources() = resources }
        attachInfo(fixtureContext, ProviderInfo().apply {
            authority = "com.promenar.nexara.test.linux.documents"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
    }
    @After fun cleanup() { deleteLinuxFixture(root) }

    @Test fun `文档id支持中文且拒绝伪造越界与非规范编码`() {
        val path = "/root/中文 a.txt"
        assertThat(LinuxDocumentsIds.decode(LinuxDocumentsIds.encode(path))).isEqualTo(path)
        listOf("", "home:%%%", "other:YWJj", "home:L3Jvb3QvLi4vZXRj").forEach {
            assertThrows(FileNotFoundException::class.java) { LinuxDocumentsIds.decode(it) }
        }
        assertThrows(FileNotFoundException::class.java) { LinuxDocumentsIds.encode("/etc/passwd") }
    }

    @Test fun `根支持剩余空间且环境未就绪时根为空并提示`() {
        provider.queryRoots(null).use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            cursor.moveToFirst()
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES))).isAtLeast(0)
        }
        state.value = LinuxRuntimeState.NotInstalled(10)
        provider.queryRoots(null).use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
            assertThat(cursor.extras.getString(DocumentsContract.EXTRA_INFO)).isNotEmpty()
        }
    }

    @Test fun `查询创建重命名删除和子文档检查按主目录授权`() {
        val homeId = LinuxDocumentsIds.encode("/root")
        val id = provider.createDocument(homeId, "text/plain", "a.txt")
        assertThat(provider.isChildDocument(homeId, id)).isTrue()
        assertThat(provider.isChildDocument(id, id)).isFalse()
        provider.queryChildDocuments(homeId, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), sortOrder = null).use {
            assertThat(it.count).isEqualTo(1)
            it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("a.txt")
        }
        val renamed = provider.renameDocument(id, "b.txt")
        assertThat(File(home, "b.txt").exists()).isTrue()
        provider.deleteDocument(renamed)
        assertThat(home.list()).isEmpty()
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument(homeId) }
    }

    @Test fun `不能经主目录链接访问根外或容器系统文件`() {
        File(root, "etc").mkdir()
        File(root, "etc/secret").writeText("secret")
        Files.createSymbolicLink(File(home, "escape").toPath(), File("/etc/secret").toPath())
        val id = LinuxDocumentsIds.encode("/root/escape")
        assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
        assertThat(provider.isChildDocument(LinuxDocumentsIds.encode("/root"), id)).isFalse()
        provider.deleteDocument(id)
        assertThat(File(root, "etc/secret").readText()).isEqualTo("secret")
    }

    @Test fun `迁移到隐藏存储的假硬链接保持SAF子文档资格`() {
        val source = File(home, "src").apply { mkdir() }
        val final = File(source, ".l2s.data0001.0002").apply { writeText("data") }
        val middle = File(source, ".l2s.data0001")
        Files.createSymbolicLink(middle.toPath(), final.toPath())
        Files.createSymbolicLink(File(home, "external").toPath(), middle.toPath())
        LinuxFileLinks(LinuxFilePaths(root)).preserveBacking(source.toPath())
        val id = LinuxDocumentsIds.encode("/root/external")
        assertThat(provider.isChildDocument(LinuxDocumentsIds.encode("/root"), id)).isTrue()
        provider.queryDocument(id, null).use { assertThat(it.count).isEqualTo(1) }
    }

    @Test fun `窄子树授权不能经过链接访问主目录内兄弟子树`() {
        File(home, "public").mkdir()
        File(home, "private").mkdir()
        File(home, "private/secret").writeText("secret")
        Files.createSymbolicLink(File(home, "public/leak").toPath(), File("/root/private/secret").toPath())
        Files.createSymbolicLink(File(home, "public/dir").toPath(), File("/root/private").toPath())
        val parent = LinuxDocumentsIds.encode("/root/public")
        assertThat(provider.isChildDocument(parent, LinuxDocumentsIds.encode("/root/public/leak"))).isFalse()
        assertThat(provider.isChildDocument(parent, LinuxDocumentsIds.encode("/root/public/dir/secret"))).isFalse()
        val local = provider.createDocument(parent, "text/plain", "local")
        assertThat(provider.isChildDocument(parent, local)).isTrue()
    }
}
