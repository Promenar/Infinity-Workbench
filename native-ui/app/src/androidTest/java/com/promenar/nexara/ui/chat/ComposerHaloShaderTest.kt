package com.promenar.nexara.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 浮岛光晕着色器能在设备上编译，并在绘制范围边缘完全透明，不会留下矩形边界。 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ComposerHaloShaderTest {
    @Test
    fun haloFadesToTransparentBeforeDrawBounds() {
        val density = 3f
        val reach = 64f * density
        val island = 300f to 120f
        val width = (island.first + reach * 2).toInt()
        val height = (island.second + reach * 2).toInt()
        val shader = RuntimeShader(ComposerHaloAgsl).apply {
            setFloatUniform("center", width / 2f, height / 2f)
            setFloatUniform("halfSize", island.first / 2f, island.second / 2f)
            setFloatUniform("radius", 24f * density)
            setFloatUniform("density", density)
            setFloatUniform("lightMode", 0f)
            setColorUniform("colorA", android.graphics.Color.rgb(160, 150, 255))
            setColorUniform("colorB", android.graphics.Color.rgb(120, 220, 255))
            setColorUniform("colorErr", android.graphics.Color.rgb(255, 80, 80))
            setFloatUniform("time", 1.3f)
            setFloatUniform("gen", 1f)
            setFloatUniform("err", 1f)
            setFloatUniform("head", 0.5f)
        }
        val bitmap = renderOnGpu(width, height) { canvas ->
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        }

        // 绘制范围最外 16dp 一圈（含四角）完全透明。
        val band = (16 * density).toInt()
        var maxEdgeAlpha = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (x < band || y < band || x >= width - band || y >= height - band) {
                maxEdgeAlpha = maxOf(maxEdgeAlpha, android.graphics.Color.alpha(bitmap.getPixel(x, y)))
            }
        }
        // 紧贴浮岛边缘明显发光。
        val nearEdge = android.graphics.Color.alpha(bitmap.getPixel(width / 2, (reach - 2 * density).toInt()))
        bitmap.recycle()

        assertThat(maxEdgeAlpha).isEqualTo(0)
        assertThat(nearEdge).isGreaterThan(20)
    }

    /** RuntimeShader 只能硬件绘制：经 HardwareRenderer 画到 ImageReader 再读回像素。 */
    private fun renderOnGpu(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val node = RenderNode("halo").apply { setPosition(0, 0, width, height) }
        val canvas = node.beginRecording()
        draw(canvas)
        node.endRecording()
        val renderer = HardwareRenderer().apply {
            isOpaque = false
            setSurface(reader.surface)
            setContentRoot(node)
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val buffer = requireNotNull(image.hardwareBuffer)
        val bitmap = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, null)).copy(Bitmap.Config.ARGB_8888, false)
        buffer.close()
        image.close()
        renderer.destroy()
        reader.close()
        return bitmap
    }
}
