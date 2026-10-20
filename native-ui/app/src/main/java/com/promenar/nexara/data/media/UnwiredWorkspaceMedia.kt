package com.promenar.nexara.data.media

import com.promenar.nexara.domain.media.MediaOrigin
import com.promenar.nexara.domain.media.MediaPayload
import com.promenar.nexara.domain.media.MediaRef
import com.promenar.nexara.domain.media.MediaResolution
import com.promenar.nexara.domain.media.MediaUnavailableReason
import com.promenar.nexara.domain.media.WorkspaceMediaResolver
import com.promenar.nexara.domain.media.WorkspaceMediaStore
import java.io.InputStream

/** 占位实现：解析一律不可用，写入一律失败；由工作区媒体实现替换后删除。 */
internal object UnwiredWorkspaceMedia : WorkspaceMediaResolver, WorkspaceMediaStore {
    override suspend fun resolve(sessionId: String, path: String): MediaResolution =
        MediaResolution.Unavailable(path, MediaUnavailableReason.UNREADABLE)

    override suspend fun open(resolution: MediaResolution.Available): InputStream =
        throw UnsupportedOperationException("工作区媒体尚未接线")

    override suspend fun save(
        sessionId: String,
        payload: MediaPayload,
        origin: MediaOrigin,
        mimeTypeHint: String?,
        suggestedName: String?,
        targetPath: String?,
    ): MediaRef = throw UnsupportedOperationException("工作区媒体尚未接线")
}
