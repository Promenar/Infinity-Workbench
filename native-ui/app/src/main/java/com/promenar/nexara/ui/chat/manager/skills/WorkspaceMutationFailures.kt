package com.promenar.nexara.ui.chat.manager.skills

import android.system.ErrnoException
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.repository.WorkspaceLifecycleException
import java.io.IOException
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

/** 只输出已知原因与异常类别，异常原文可能包含设备上的物理路径。 */
internal fun workspaceMutationFailureResult(operation: String, failure: Exception): ToolResult {
    val errno = when (failure) {
        is ErrnoException -> failure.errno
        is IOException -> NATIVE_MOVE_FAILURE.matchEntire(failure.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        else -> null
    }
    val platformReason = errno?.let {
        when (it) {
            ANDROID_EACCES, ANDROID_EPERM -> "权限不足，请检查文件夹授权及读写权限"
            ANDROID_ENOENT -> "条目不存在或已被移动，请用 list_files 重新定位"
            ANDROID_EXDEV -> "不支持跨文件系统移动，请选择同一文件系统中的目标目录"
            else -> null
        }
    }
    val reason = platformReason ?: when (failure) {
        is FileAlreadyExistsException -> "目标已存在，请选择其它名称或目录"
        is NoSuchFileException, is FileNotFoundException -> "条目不存在或已被移动，请用 list_files 重新定位"
        is AccessDeniedException -> "权限不足，请检查文件夹授权及读写权限"
        is SecurityException -> if (failure.message?.contains("不属于当前工作区") == true) {
            "目标跨工作区，条目与父目录必须属于当前工作区"
        } else "权限不足或操作被工作区安全策略拒绝（名称、路径或目标不合法）"
        // 仓库层专为调用方编写的提示，只含工作区虚拟路径。
        is WorkspaceLifecycleException -> failure.safeMessage.take(200)
        is IllegalStateException -> when {
            failure.message?.contains("已存在") == true -> "目标已存在，请选择其它名称或目录"
            failure.message?.contains("expected_sha256") == true -> "文件版本冲突，请用 read_file 获取最新版本"
            else -> "工作区状态冲突，请重新读取条目后重试"
        }
        else -> "工作区操作失败（${failure.javaClass.simpleName.ifEmpty { "Exception" }}），请检查条目状态与存储权限"
    }
    return workspaceToolError(operation, reason)
}

// 同步目录的失败可能发生在移动之后，不作为移动前的 errno 拒绝分类。
private val NATIVE_MOVE_FAILURE = Regex("""(?:打开移动目录FD|不覆盖移动)失败\(errno=(\d+)\)，未降低文件身份保证""")

// Android/Linux errno ABI；同时用于解析原生桥接输出的十进制 errno。
private const val ANDROID_EPERM = 1
private const val ANDROID_ENOENT = 2
private const val ANDROID_EACCES = 13
private const val ANDROID_EXDEV = 18
