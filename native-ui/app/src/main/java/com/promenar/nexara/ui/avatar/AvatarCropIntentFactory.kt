package com.promenar.nexara.ui.avatar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity

internal data class AvatarCropPalette(
    val surface: Int,
    val onSurface: Int,
    val primary: Int,
    val scrim: Int,
    val outline: Int,
)

internal object AvatarCropIntentFactory {
    const val OUTPUT_SIZE = 512
    internal const val EXTRA_SURFACE_COLOR = "com.promenar.nexara.avatar.SURFACE_COLOR"
    internal const val EXTRA_ON_SURFACE_COLOR = "com.promenar.nexara.avatar.ON_SURFACE_COLOR"
    internal const val EXTRA_LIGHT_SYSTEM_BARS = "com.promenar.nexara.avatar.LIGHT_SYSTEM_BARS"
    internal const val EXTRA_COMPLETION_TITLE = "com.promenar.nexara.avatar.COMPLETION_TITLE"

    fun create(
        context: Context,
        source: Uri,
        destination: Uri,
        title: String,
        completionTitle: String,
        palette: AvatarCropPalette,
        lightSystemBars: Boolean = false,
    ): Intent {
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(95)
            setAllowedGestures(UCropActivity.ALL, UCropActivity.ALL, UCropActivity.ALL)
            setCircleDimmedLayer(true)
            setDimmedLayerColor(palette.scrim)
            setShowCropFrame(false)
            setShowCropGrid(true)
            setCropGridRowCount(2)
            setCropGridColumnCount(2)
            setCropGridColor(palette.outline)
            setToolbarColor(palette.surface)
            setStatusBarColor(palette.surface)
            setToolbarWidgetColor(palette.onSurface)
            setActiveControlsWidgetColor(palette.primary)
            setLogoColor(palette.onSurface)
            setRootViewBackgroundColor(palette.surface)
            setHideBottomControls(true)
            setFreeStyleCropEnabled(false)
            setToolbarTitle(title)
        }
        return UCrop.of(source, destination)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(OUTPUT_SIZE, OUTPUT_SIZE)
            .withOptions(options)
            .getIntent(context)
            .setClass(context, AvatarCropActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(EXTRA_SURFACE_COLOR, palette.surface)
            .putExtra(EXTRA_ON_SURFACE_COLOR, palette.onSurface)
            .putExtra(EXTRA_LIGHT_SYSTEM_BARS, lightSystemBars)
            .putExtra(EXTRA_COMPLETION_TITLE, completionTitle)
    }
}
