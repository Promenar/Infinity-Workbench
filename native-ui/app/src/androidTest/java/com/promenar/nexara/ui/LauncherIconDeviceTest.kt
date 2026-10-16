package com.promenar.nexara.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 验证真实 Android 资源解析；可显式指向已安装的正式签名 APK。 */
@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetPackage = InstrumentationRegistry.getArguments()
        .getString("launcherIconTargetPackage") ?: instrumentation.targetContext.packageName

    private fun context(night: Boolean): Context {
        val app = instrumentation.targetContext.createPackageContext(targetPackage, 0)
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return app.createConfigurationContext(configuration)
    }

    private fun icon(context: Context, name: String): AdaptiveIconDrawable {
        val id = context.resources.getIdentifier(name, "mipmap", targetPackage)
        assertThat(id).isNotEqualTo(0)
        val drawable = context.resources.getDrawable(id, context.theme)
        assertThat(drawable).isInstanceOf(AdaptiveIconDrawable::class.java)
        return drawable as AdaptiveIconDrawable
    }

    private fun render(drawable: Drawable, size: Int = 108): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(it))
        }

    @Test
    fun normalAndRoundIconsResolveToBlackOnWhiteAndWhiteOnBlack() {
        for (night in listOf(false, true)) {
            val context = context(night)
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val icon = icon(context, name)
                val background = render(icon.background)
                val foreground = render(icon.foreground)
                assertThat(background.getPixel(54, 54)).isEqualTo(if (night) Color.BLACK else Color.WHITE)
                for ((x, y) in listOf(30 to 58, 54 to 44, 76 to 58)) {
                    assertThat(foreground.getPixel(x, y)).isEqualTo(if (night) Color.WHITE else Color.BLACK)
                }
                assertThat(Color.alpha(foreground.getPixel(68, 58))).isEqualTo(0)
                assertThat(Color.alpha(foreground.getPixel(54, 72))).isEqualTo(0)
            }
            val output = File(instrumentation.targetContext.filesDir, "launcher-icon-${if (night) "dark" else "light"}.png")
            output.outputStream().use { render(icon(context, "ic_launcher"), 256).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun monochromeIsTheSameTransparentSilhouetteInBothModes() {
        for (night in listOf(false, true)) {
            val icon = icon(context(night), "ic_launcher")
            val mono = icon.monochrome
            assertThat(mono).isNotNull()
            val mask = render(requireNotNull(mono))
            val foreground = render(icon.foreground)
            var ink = 0
            for (y in 0 until 108) for (x in 0 until 108) {
                assertThat(Color.alpha(mask.getPixel(x, y))).isEqualTo(Color.alpha(foreground.getPixel(x, y)))
                if (Color.alpha(mask.getPixel(x, y)) > 200) {
                    ink++
                    val dx = x + .5 - 54
                    val dy = y + .5 - 54
                    assertThat(dx * dx + dy * dy).isAtMost(33.0 * 33.0)
                }
            }
            assertThat(ink).isGreaterThan(900)
        }
    }
}
