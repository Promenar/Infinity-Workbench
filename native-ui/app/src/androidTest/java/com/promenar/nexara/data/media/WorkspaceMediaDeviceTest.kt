package com.promenar.nexara.data.media

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.media.MediaKind
import com.promenar.nexara.domain.media.MediaOrigin
import com.promenar.nexara.domain.media.MediaPayload
import com.promenar.nexara.domain.media.MediaResolution
import com.promenar.nexara.data.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.UUID

/**
 * 应用真实组件在设备上把媒体写入会话私有工作区：直接保存字节与 media_fetch 下载两条入口，
 * 写入后同一解析器可解析出带元数据的媒体。下载用例需要设备能访问公网。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceMediaDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NexaraApplication
    private val sessionId = "media-device-${UUID.randomUUID()}"

    @Before
    fun setUp() = runBlocking {
        app.sessionRepository.create(Session(id = sessionId, agentId = "media-device-agent", title = "媒体设备测试"))
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { app.sessionRepository.delete(sessionId) }
        Unit
    }

    @Test
    fun 字节写入指定路径后可解析为图片() = runBlocking {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ref = saving { app.workspaceMediaStore.save(sessionId, MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD, "image/png", "dot.png", "/media/test/dot.png") }
        assertThat(ref.path).isEqualTo("/media/test/dot.png")
        assertThat(ref.kind).isEqualTo(MediaKind.IMAGE)
        val resolved = app.workspaceMediaResolver.resolve(sessionId, ref.path) as MediaResolution.Available
        assertThat(resolved.ref.width).isEqualTo(4)
        assertThat(resolved.ref.height).isEqualTo(3)
    }

    @Test
    fun 下载公网视频写入工作区() = runBlocking {
        assumeTrue("设备无法解析公网域名", runCatching { InetAddress.getByName("www.w3schools.com") }.isSuccess)
        val ref = saving {
            app.workspaceMediaDownloader.save("https://www.w3schools.com/html/mov_bbb.mp4", sessionId, app.workspaceMediaStore, MediaOrigin.DOWNLOAD, "/media/test/bbb.mp4")
        }
        assertThat(ref.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(ref.sizeBytes).isGreaterThan(100_000L)
        assertThat(ref.durationMs).isGreaterThan(9_000L)
    }

    /** 失败时把完整原因链写进断言信息，便于定位被包装为 IO 的底层异常。 */
    private suspend fun <T> saving(block: suspend () -> T): T = try {
        block()
    } catch (failure: Exception) {
        assertWithMessage(Log.getStackTraceString(failure)).fail()
        throw failure
    }
}
