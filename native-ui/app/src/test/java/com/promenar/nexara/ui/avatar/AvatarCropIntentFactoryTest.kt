package com.promenar.nexara.ui.avatar

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yalantis.ucrop.UCrop
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AvatarCropIntentFactoryTest {

    @Test
    fun `avatar crop intent uses square bounded output and themed explicit completion activity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = Uri.parse("content://fixture/source")
        val destination = Uri.parse("file:///cache/avatar-crops/result.jpg")
        val palette = AvatarCropPalette(
            surface = 0xff111111.toInt(),
            onSurface = 0xffeeeeee.toInt(),
            primary = 0xff8877ff.toInt(),
            scrim = 0xb3000000.toInt(),
            outline = 0xffaaaabb.toInt(),
        )

        val intent = AvatarCropIntentFactory.create(
            context = context,
            source = source,
            destination = destination,
            title = "移动和缩放",
            completionTitle = "使用照片",
            palette = palette,
        )

        assertThat(intent.component?.className).isEqualTo(AvatarCropActivity::class.java.name)
        assertThat(intent.getParcelableExtra<Uri>(UCrop.EXTRA_INPUT_URI)).isEqualTo(source)
        assertThat(intent.getParcelableExtra<Uri>(UCrop.EXTRA_OUTPUT_URI)).isEqualTo(destination)
        assertThat(intent.getFloatExtra(UCrop.EXTRA_ASPECT_RATIO_X, 0f)).isEqualTo(1f)
        assertThat(intent.getFloatExtra(UCrop.EXTRA_ASPECT_RATIO_Y, 0f)).isEqualTo(1f)
        assertThat(intent.getIntExtra(UCrop.EXTRA_MAX_SIZE_X, 0)).isEqualTo(512)
        assertThat(intent.getIntExtra(UCrop.EXTRA_MAX_SIZE_Y, 0)).isEqualTo(512)
        assertThat(intent.getBooleanExtra(UCrop.Options.EXTRA_CIRCLE_DIMMED_LAYER, false)).isTrue()
        assertThat(intent.getBooleanExtra(UCrop.Options.EXTRA_SHOW_CROP_GRID, false)).isTrue()
        assertThat(intent.getBooleanExtra(UCrop.Options.EXTRA_HIDE_BOTTOM_CONTROLS, false)).isTrue()
        assertThat(intent.getIntExtra(UCrop.Options.EXTRA_TOOL_BAR_COLOR, 0)).isEqualTo(palette.surface)
        assertThat(intent.getIntExtra(UCrop.Options.EXTRA_UCROP_WIDGET_COLOR_TOOLBAR, 0))
            .isEqualTo(palette.onSurface)
        assertThat(intent.getIntExtra(AvatarCropIntentFactory.EXTRA_ON_SURFACE_COLOR, 0))
            .isEqualTo(palette.onSurface)
        assertThat(intent.getIntExtra(UCrop.Options.EXTRA_UCROP_ROOT_VIEW_BACKGROUND_COLOR, 0))
            .isEqualTo(palette.surface)
        assertThat(intent.getStringExtra(AvatarCropIntentFactory.EXTRA_COMPLETION_TITLE))
            .isEqualTo("使用照片")
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
    }
}
