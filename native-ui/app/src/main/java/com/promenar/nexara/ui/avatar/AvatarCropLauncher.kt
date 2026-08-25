package com.promenar.nexara.ui.avatar

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.yalantis.ucrop.UCrop
import java.io.File
import java.util.UUID

internal class AvatarCropLauncher internal constructor(
    private val launchPicker: () -> Unit,
) {
    fun launch() = launchPicker()
}

/** Agent 与用户头像共用同一选择、圆形预览、手势缩放和方形输出合同。 */
@Composable
internal fun rememberAvatarCropLauncher(
    onCropped: (Uri) -> Unit,
    onFailure: () -> Unit = {},
): AvatarCropLauncher {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val title = stringResource(R.string.avatar_crop_title)
    val completionTitle = stringResource(R.string.avatar_crop_use_photo)
    val lightSystemBars = !isSystemInDarkTheme()
    val currentOnCropped by rememberUpdatedState(onCropped)
    val currentOnFailure by rememberUpdatedState(onFailure)
    var pendingOutput by remember { mutableStateOf<Uri?>(null) }

    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val output = result.data?.let(UCrop::getOutput)
        when {
            result.resultCode == Activity.RESULT_OK && output != null -> currentOnCropped(output)
            result.resultCode == UCrop.RESULT_ERROR -> {
                AvatarCropFiles.deleteIfOwned(context.cacheDir, pendingOutput)
                currentOnFailure()
            }
            else -> AvatarCropFiles.deleteIfOwned(context.cacheDir, pendingOutput)
        }
        pendingOutput = null
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { source ->
        if (source != null) {
            val destination = AvatarCropFiles.createDestination(context.cacheDir)
            pendingOutput = destination
            cropLauncher.launch(
                AvatarCropIntentFactory.create(
                    context = context,
                    source = source,
                    destination = destination,
                    title = title,
                    completionTitle = completionTitle,
                    palette = AvatarCropPalette(
                        surface = colorScheme.surface.toArgb(),
                        onSurface = colorScheme.onSurface.toArgb(),
                        primary = colorScheme.primary.toArgb(),
                        scrim = colorScheme.scrim.copy(alpha = 0.72f).toArgb(),
                        outline = colorScheme.outline.toArgb(),
                    ),
                    lightSystemBars = lightSystemBars,
                ),
            )
        }
    }

    return remember(photoPicker) {
        AvatarCropLauncher {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

internal object AvatarCropFiles {
    private const val DIRECTORY = "avatar-crops"

    fun createDestination(cacheDirectory: File): Uri {
        val directory = File(cacheDirectory, DIRECTORY)
        check(directory.exists() || directory.mkdirs()) { "无法创建头像裁剪缓存目录" }
        directory.listFiles()
            ?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > MAX_AGE_MILLIS }
            ?.forEach(File::delete)
        return Uri.fromFile(File(directory, "crop-${UUID.randomUUID()}.jpg"))
    }

    fun deleteIfOwned(cacheDirectory: File, uri: Uri?) {
        if (uri?.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        val expectedParent = File(cacheDirectory, DIRECTORY)
        val owned = runCatching {
            file.canonicalFile.parentFile == expectedParent.canonicalFile
        }.getOrDefault(false)
        if (owned) file.delete()
    }

    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
}
