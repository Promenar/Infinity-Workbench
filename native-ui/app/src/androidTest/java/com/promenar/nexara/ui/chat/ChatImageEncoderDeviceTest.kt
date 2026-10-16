package com.promenar.nexara.ui.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * 在真实设备上以 MediaStore 的 content:// 图片走一遍附件编码，
 * 与用户从相册选图后发送的读取路径一致（先只读尺寸，再按采样率解码）。
 */
class ChatImageEncoderDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()

    @After
    fun tearDown() {
        inserted.forEach { uri -> context.contentResolver.delete(uri, null, null) }
    }

    @Test
    fun mediaStorePngScreenshotEncodesToJpegDataUrl() {
        val uri = insertImage(width = 1080, height = 2400, format = Bitmap.CompressFormat.PNG, mime = "image/png")

        val dataUrl = ChatImageEncoder.encodeToDataUrl { context.contentResolver.openInputStream(uri) }

        assertThat(dataUrl).startsWith("data:image/jpeg;base64,")
        val bytes = Base64.decode(requireNotNull(dataUrl).substringAfter("base64,"), Base64.NO_WRAP)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        // 长边 2400 ≥ 2×1568 不成立，采样率为 1，尺寸保持不变。
        assertThat(decoded.width).isEqualTo(1080)
        assertThat(decoded.height).isEqualTo(2400)
    }

    @Test
    fun largeMediaStoreJpegIsDownsampled() {
        val uri = insertImage(width = 4000, height = 3000, format = Bitmap.CompressFormat.JPEG, mime = "image/jpeg")

        val dataUrl = ChatImageEncoder.encodeToDataUrl { context.contentResolver.openInputStream(uri) }

        val bytes = Base64.decode(requireNotNull(dataUrl).substringAfter("base64,"), Base64.NO_WRAP)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertThat(decoded.width).isEqualTo(2000)
        assertThat(decoded.height).isEqualTo(1500)
    }

    private fun insertImage(width: Int, height: Int, format: Bitmap.CompressFormat, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "nexara-encoder-test-${System.nanoTime()}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NexaraDeviceTest")
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        )
        inserted += uri
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 90, 160))
        }
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            bitmap.compress(format, 90, output)
        }
        bitmap.recycle()
        return uri
    }
}
