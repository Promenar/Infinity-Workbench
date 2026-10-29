package com.promenar.infinity.ui.chat.export

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.export.SessionExportFile
import com.promenar.infinity.domain.export.SessionExportResult
import com.promenar.infinity.ui.common.SnackbarType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** 面板与 `/export <格式>` 在同一个 ViewModel 存储里按会话共用同一实例。 */
@Composable
fun rememberSessionExportViewModel(sessionId: String): SessionExportViewModel {
    val application = LocalContext.current.applicationContext as Application
    return viewModel(
        key = "session_export:$sessionId",
        factory = remember(sessionId, application) {
            viewModelFactory {
                initializer {
                    SessionExportViewModel(
                        sessionId = sessionId,
                        exporter = (application as InfinityApplication).sessionExporter,
                        writer = ContentResolverExportWriter(application),
                    )
                }
            }
        },
    )
}

/**
 * 导出面板及其系统交互：“另存为”/选文件夹、分享面板与结果提示。
 * 始终放在聊天页里，面板关闭时也负责 `/export <格式>` 的保存流程。
 */
@Composable
fun SessionExportHost(
    viewModel: SessionExportViewModel,
    show: Boolean,
    sessionTitle: String,
    messageCount: Int,
    onDismiss: () -> Unit,
    showFeedback: (String, SnackbarType) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestShow by rememberUpdatedState(show)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestFeedback by rememberUpdatedState(showFeedback)

    val createDocument = rememberLauncherForActivityResult(CreateTypedDocument(), viewModel::onSaveTarget)
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), viewModel::onSaveTarget)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionExportEvent.PickSaveTarget -> try {
                    val files = event.result.files
                    if (files.size == 1) createDocument.launch(files.single()) else openFolder.launch(null)
                } catch (_: ActivityNotFoundException) {
                    viewModel.onSaveTarget(null)
                    latestFeedback(resources.getString(R.string.session_export_no_target), SnackbarType.ERROR)
                }
                is SessionExportEvent.Share -> {
                    try {
                        context.startActivity(
                            Intent.createChooser(
                                buildSessionExportShareIntent(context, event.result),
                                resources.getString(R.string.session_export_share_chooser),
                            ),
                        )
                        if (latestShow) latestDismiss()
                    } catch (_: ActivityNotFoundException) {
                        latestFeedback(resources.getString(R.string.session_export_no_target), SnackbarType.ERROR)
                    } catch (_: IllegalArgumentException) {
                        latestFeedback(resources.getString(R.string.session_export_failed), SnackbarType.ERROR)
                    }
                }
                is SessionExportEvent.Saved -> {
                    if (latestShow) latestDismiss()
                    latestFeedback(
                        if (event.fileCount == 1) resources.getString(R.string.session_export_saved, event.displayName)
                        else resources.getQuantityString(R.plurals.session_export_saved_files, event.fileCount, event.fileCount),
                        SnackbarType.SUCCESS,
                    )
                }
                SessionExportEvent.ExportFailed ->
                    latestFeedback(resources.getString(R.string.session_export_failed), SnackbarType.ERROR)
                SessionExportEvent.SaveFailed ->
                    latestFeedback(resources.getString(R.string.session_export_save_failed), SnackbarType.ERROR)
            }
        }
    }
    LaunchedEffect(show) { if (show) viewModel.refreshEstimate() }

    val actions = remember(viewModel) {
        SessionExportSheetActions(
            onFormat = viewModel::selectFormat,
            onOptions = viewModel::updateOptions,
            onSave = viewModel::save,
            onShare = viewModel::share,
            onCancel = viewModel::cancel,
        )
    }
    SessionExportSheet(
        show = show,
        sessionTitle = sessionTitle,
        messageCount = messageCount,
        state = state,
        actions = actions,
        onDismiss = {
            viewModel.cancel()
            onDismiss()
        },
    )
}

/** 带 MIME 与建议文件名的系统“另存为”。 */
private class CreateTypedDocument : ActivityResultContract<SessionExportFile, Uri?>() {
    override fun createIntent(context: Context, input: SessionExportFile): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.displayName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/** 多个文件的分享类型：同一 MIME 用它本身，同属一个大类时用该大类的通配类型，否则用任意类型。 */
internal fun sessionExportShareMimeType(mimeTypes: List<String>): String {
    val distinct = mimeTypes.distinct()
    distinct.singleOrNull()?.let { return it }
    return distinct.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
}

/** 单个文件用 ACTION_SEND，多张长图或主文件加 zip 用 ACTION_SEND_MULTIPLE；经 FileProvider 授予读取权限。 */
internal fun buildSessionExportShareIntent(context: Context, result: SessionExportResult): Intent {
    val authority = "${context.packageName}.fileprovider"
    val uris = result.files.map { FileProvider.getUriForFile(context, authority, it.file, it.displayName) }
    val mimeType = sessionExportShareMimeType(result.files.map { it.mimeType })
    val clip = ClipData.newUri(context.contentResolver, result.files.first().displayName, uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    return intent
        .setType(mimeType)
        .putExtra(Intent.EXTRA_TITLE, result.files.first().displayName)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .also { it.clipData = clip }
}

/** 经 ContentResolver 写入系统选择器返回的文档或文件夹。 */
internal class ContentResolverExportWriter(private val context: Context) : SessionExportTargetWriter {
    override suspend fun write(result: SessionExportResult, target: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (!DocumentsContract.isTreeUri(target)) {
            copy(result.files.single(), target)
            return@withContext
        }
        val folder = DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target))
        result.files.forEach { file ->
            ensureActive()
            val document = DocumentsContract.createDocument(resolver, folder, file.mimeType, file.displayName)
                ?: throw IOException("无法在所选文件夹中创建 ${file.displayName}")
            copy(file, document)
        }
    }

    private fun copy(file: SessionExportFile, target: Uri) {
        val output = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("无法写入 $target")
        output.use { out -> file.file.inputStream().use { it.copyTo(out) } }
    }
}
