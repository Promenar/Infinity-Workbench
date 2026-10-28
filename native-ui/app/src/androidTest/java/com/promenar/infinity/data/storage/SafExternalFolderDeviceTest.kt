package com.promenar.infinity.data.storage

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.MainActivity
import com.promenar.infinity.domain.storage.ExternalFolderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafExternalFolderDeviceTest {
    @Test
    fun realResultGrantPersistsAfterReceivingActivityFinishesAndBackendRoundTrips() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val fixturePackage = com.promenar.infinity.test.BuildConfig.APPLICATION_ID
        val tree = DocumentsContract.buildTreeDocumentUri("$fixturePackage.externalfoldersfixture", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.persistedUriPermissions.filter { it.uri == tree }.forEach {
            app.contentResolver.releasePersistableUriPermission(it.uri, flags)
        }
        val beforeGrant = try {
            SafExternalFolderBackend(app.contentResolver, tree).stat("/")
            null
        } catch (exception: ExternalFolderException) {
            exception
        }
        assertEquals("跨 UID 提供商在系统授权前必须不可读", "UNAVAILABLE", beforeGrant?.code)
        val receiver = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        try {
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                receiver.startActivityForResult(Intent().apply {
                    component = ComponentName(fixturePackage, ExternalFolderGrantActivity::class.java.name)
                }, 31781)
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var persisted = false
            while (!persisted && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                try {
                    app.contentResolver.takePersistableUriPermission(tree, flags)
                    persisted = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
                } catch (_: SecurityException) {
                    SystemClock.sleep(50)
                }
            }
            assertTrue("目标应用必须实际取得跨 UID 的持久 URI 授权", persisted)
        } finally {
            instrumentation.runOnMainSync { receiver.finish() }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!receiver.isDestroyed && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                instrumentation.waitForIdleSync()
            }
            assertTrue("接收临时授权的 Activity 必须结束", receiver.isDestroyed)
        }

        val backend = SafExternalFolderBackend(app.contentResolver, tree)
        val directory = "/fixture-${UUID.randomUUID()}"
        try {
            backend.createDirectory(directory)
            val written = backend.write("$directory/note.txt", "设备 SAF 测试".toByteArray(), createOnly = true)
            assertArrayEquals("设备 SAF 测试".toByteArray(), backend.read("$directory/note.txt"))
            assertEquals(listOf("note.txt"), backend.list(directory).map { it.name })
            val updated = backend.write("$directory/note.txt", "持久授权".toByteArray(), written.sha256)
            assertEquals(updated.sha256, backend.stat("$directory/note.txt")!!.sha256)
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        } finally {
            try {
                if (backend.stat(directory) != null) backend.delete(directory)
            } finally {
                app.contentResolver.releasePersistableUriPermission(tree, flags)
            }
        }
        val afterRelease = try {
            SafExternalFolderBackend(app.contentResolver, tree).stat("/")
            null
        } catch (exception: ExternalFolderException) {
            exception
        }
        assertEquals("释放持久授权后必须无法访问测试树", "UNAVAILABLE", afterRelease?.code)
    }
}
