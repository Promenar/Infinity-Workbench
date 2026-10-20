package com.promenar.nexara.domain.media

/** 单个媒体载荷的最大大小；流式载荷超限后立即停止读取。 */
const val MAX_WORKSPACE_MEDIA_BYTES: Long = 256L * 1024 * 1024

/** 可直接展示给模型的媒体存储错误；[code] 为稳定的机器可读分类。 */
class WorkspaceMediaException(val code: String, override val message: String) : Exception(message)
