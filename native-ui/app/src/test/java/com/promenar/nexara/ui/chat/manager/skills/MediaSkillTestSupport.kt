package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.domain.media.*
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.util.Base64

internal val mediaTestContext = object : SkillExecutionContext {
    override val sessionId = "media-session"
    override val agentId = "agent"
    override val workspacePath: String? = null
    override val workspaceRootUuid = "root"
}

internal val mediaTestPng = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + ByteArray(32) { 1 }
internal fun mediaBase64(bytes: ByteArray = mediaTestPng): String = Base64.getEncoder().encodeToString(bytes)

internal class RecordingMediaStore : WorkspaceMediaStore {
    data class Saved(val session: String, val bytes: ByteArray, val origin: MediaOrigin, val mime: String?, val name: String?, val path: String?)
    val saved = mutableListOf<Saved>()
    var failure: WorkspaceMediaException? = null
    override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
        failure?.let { throw it }
        val bytes = when (payload) {
            is MediaPayload.Bytes -> payload.bytes
            is MediaPayload.Stream -> payload.open().use { it.readBytes() }
        }
        saved += Saved(sessionId, bytes, origin, mimeTypeHint, suggestedName, targetPath)
        val mime = mimeTypeHint ?: "image/png"
        val kind = when (mime.substringBefore('/')) {
            "image" -> MediaKind.IMAGE
            "audio" -> MediaKind.AUDIO
            "video" -> MediaKind.VIDEO
            else -> MediaKind.FILE
        }
        return MediaRef(targetPath ?: "/media/2026-10-20/${origin.prefix}-${saved.size}.png", mime, kind, bytes.size.toLong(), width = if (kind == MediaKind.IMAGE) 3 else null, height = if (kind == MediaKind.IMAGE) 2 else null, durationMs = if (kind == MediaKind.AUDIO) 1200 else null)
    }
}
