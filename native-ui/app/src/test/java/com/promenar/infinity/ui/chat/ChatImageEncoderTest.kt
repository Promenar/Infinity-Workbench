package com.promenar.infinity.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatImageEncoderTest {

    @Test
    fun `PNG 截图可编码为 JPEG data URL`() {
        val png = encodedImage(320, 200, Bitmap.CompressFormat.PNG)

        val dataUrl = ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream(png) }

        assertThat(dataUrl).startsWith("data:image/jpeg;base64,")
        val decoded = decodeDataUrl(requireNotNull(dataUrl))
        assertThat(decoded.width).isEqualTo(320)
        assertThat(decoded.height).isEqualTo(200)
    }

    @Test
    fun `超大图片按长边降采样`() {
        val jpeg = encodedImage(4000, 1000, Bitmap.CompressFormat.JPEG)

        val dataUrl = ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream(jpeg) }

        val decoded = decodeDataUrl(requireNotNull(dataUrl))
        assertThat(decoded.width).isEqualTo(2000)
        assertThat(decoded.height).isEqualTo(500)
    }

    @Test
    fun `非图片内容与无法打开的流返回 null`() {
        assertThat(ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream("not an image".toByteArray()) }).isNull()
        assertThat(ChatImageEncoder.encodeToDataUrl { null }).isNull()
    }

    @Test
    fun `采样率保证解码长边不低于上限的一半`() {
        assertThat(ChatImageEncoder.sampleSizeFor(1000, 800)).isEqualTo(1)
        assertThat(ChatImageEncoder.sampleSizeFor(3136, 100)).isEqualTo(2)
        assertThat(ChatImageEncoder.sampleSizeFor(4000, 3000)).isEqualTo(2)
        assertThat(ChatImageEncoder.sampleSizeFor(12544, 100)).isEqualTo(8)
    }

    private fun encodedImage(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 120, 200))
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(format, 90, output)
            output.toByteArray()
        }
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap {
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.NO_WRAP)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}
