package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.domain.media.MediaRef
import com.promenar.nexara.domain.media.WorkspaceMediaException

internal fun mediaSavedDescription(ref: MediaRef): String {
    val dimensions = when {
        ref.width != null && ref.height != null -> "${ref.width}×${ref.height}"
        ref.durationMs != null -> "时长 ${ref.durationMs} 毫秒"
        else -> "尺寸或时长未知"
    }
    return "已保存到 ${ref.path}（${ref.mimeType}，$dimensions，${ref.sizeBytes} 字节）"
}

internal fun mediaReferenceGuidance(ref: MediaRef): String =
    "在回复中用 ![说明](${ref.path}) 展示给用户；普通文件链接可用 [名称](${ref.path})。"

/** 网络异常可能包含带签名的 URL；只向模型返回分类，不回显下载地址或载荷。 */
internal fun mediaFailureDescription(error: Exception): String = when (error) {
    is WorkspaceMediaException -> error.message
    is com.promenar.nexara.domain.storage.ExternalFolderException -> error.message
    is java.net.SocketTimeoutException -> "媒体下载超时，请重试"
    is java.io.IOException -> "媒体下载或读取失败，请检查网络与工作区"
    else -> "媒体处理失败，请检查参数与工作区"
}
