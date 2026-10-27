package com.promenar.nexara.ui.canvas

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.promenar.nexara.R
import com.promenar.nexara.data.artifact.ArtifactFileSnapshot
import com.promenar.nexara.data.artifact.ArtifactKind
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.media.MediaFileActions
import com.promenar.nexara.ui.renderer.ImageLightbox
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用内预览的文本上限；更大的文本只提供用其它应用打开。 */
private const val MAX_TEXT_PREVIEW_BYTES = 512L * 1024L

/** 文件产物在应用内的呈现方式。 */
internal enum class FileArtifactPreview { IMAGE, MARKDOWN, TEXT, PAGE, EXTERNAL }

internal fun fileArtifactPreview(kind: ArtifactKind, fileName: String, sizeBytes: Long): FileArtifactPreview {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val smallText = sizeBytes <= MAX_TEXT_PREVIEW_BYTES
    return when {
        kind == ArtifactKind.IMAGE && extension != "svg" -> FileArtifactPreview.IMAGE
        extension in setOf("md", "markdown") && smallText -> FileArtifactPreview.MARKDOWN
        extension in setOf("txt", "csv", "tsv", "json", "log", "yaml", "yml", "xml") && smallText -> FileArtifactPreview.TEXT
        extension in setOf("html", "htm", "svg") && sizeBytes <= 1_048_576L -> FileArtifactPreview.PAGE
        else -> FileArtifactPreview.EXTERNAL
    }
}

/**
 * 文件产物查看：图片进图片查看器，Markdown 与纯文本在应用内显示，HTML 与 SVG 用画布网页只读显示，
 * 其它类型（PDF、Office、压缩包等）直接交给其它应用打开。
 */
@Composable
internal fun FileArtifactViewer(
    snapshot: ArtifactFileSnapshot?,
    error: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openWith: () -> Unit = { snapshot?.let { scope.launch { openArtifactExternally(context, it) } } }
    val preview = snapshot?.let { fileArtifactPreview(it.summary.kind, it.summary.fileName ?: it.file.name, it.file.length()) }
    if (snapshot != null && preview == FileArtifactPreview.IMAGE) {
        ImageLightbox(
            imageUrl = Uri.fromFile(snapshot.file).toString(),
            model = snapshot.file,
            onDismiss = onClose,
            overlay = {
                FilledTonalButton(onClick = openWith, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = NexaraSpacing.XLarge)) {
                    Text(stringResource(R.string.artifact_open_with))
                }
            },
        )
        return
    }
    if (snapshot != null && preview == FileArtifactPreview.EXTERNAL) {
        LaunchedEffect(snapshot) { openArtifactExternally(context, snapshot); onClose() }
        return
    }
    var text by remember(snapshot) { mutableStateOf<String?>(null) }
    var readFailed by remember(snapshot) { mutableStateOf(false) }
    LaunchedEffect(snapshot) {
        if (snapshot == null) return@LaunchedEffect
        try {
            text = withContext(Dispatchers.IO) { snapshot.file.readText(Charsets.UTF_8) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readFailed = true
        }
    }
    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.ARTIFACT_FILE_VIEWER), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = NexaraSpacing.XSmall, vertical = NexaraSpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(horizontal = NexaraSpacing.Small)) {
                    Text(
                        text = snapshot?.summary?.title.orEmpty(),
                        style = NexaraTypography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    snapshot?.let {
                        Text(text = artifactMeta(it.summary), style = NexaraTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                IconButton(onClick = openWith, enabled = snapshot != null, modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.artifact_open_with))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.canvas_close))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val content = text
                when {
                    error || readFailed -> Text(
                        text = stringResource(R.string.artifact_file_load_error),
                        color = MaterialTheme.colorScheme.error,
                        style = NexaraTypography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(NexaraSpacing.XLarge),
                    )
                    content == null -> CanvasLoading()
                    preview == FileArtifactPreview.PAGE -> CanvasWebView(html = content, modifier = Modifier.fillMaxSize(), interactive = true, readOnly = true)
                    preview == FileArtifactPreview.MARKDOWN -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NexaraSpacing.Large),
                    ) { MarkdownText(markdown = content) }
                    else -> SelectionContainer {
                        Text(
                            text = content,
                            style = NexaraTypography.bodyMedium.copy(fontFamily = NexaraMonospace),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NexaraSpacing.Large),
                        )
                    }
                }
            }
        }
    }
}

/** 复制到分享缓存目录（文件名用产物原名），再以只读 content URI 交给其它应用。 */
internal suspend fun openArtifactExternally(context: Context, snapshot: ArtifactFileSnapshot) {
    val uri = try {
        withContext(Dispatchers.IO) {
            MediaFileActions.cleanupShareCache(context)
            val directory = File(MediaFileActions.shareDirectory(context), UUID.randomUUID().toString()).apply { mkdirs() }
            val name = (snapshot.summary.fileName ?: snapshot.file.name).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120).ifBlank { "artifact" }
            val copy = File(directory, name)
            snapshot.file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Toast.makeText(context, R.string.artifact_file_load_error, Toast.LENGTH_SHORT).show()
        return
    }
    val mime = snapshot.summary.mimeType?.takeIf { it.contains('/') } ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(snapshot.summary.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(view, null).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.artifact_no_app, Toast.LENGTH_SHORT).show()
    }
}
