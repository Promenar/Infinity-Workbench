package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.ModelCapabilities
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.remote.protocol.ProtocolType

/** 每次请求实际路由的模型与接口；未知能力保持关闭，不根据名称猜测。 */
data class MediaViewTarget(
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val protocol: ProtocolType = ProtocolType.OpenAI_ChatCompletions,
    val modelId: String = "",
) {
    val canView: Boolean get() = capabilities.vision || capabilities.audioInput || capabilities.videoUnderstanding
    val supportsImages: Boolean get() = capabilities.vision && protocol != ProtocolType.Local

    fun supportsAudio(mimeType: String): Boolean = capabilities.audioInput && when (protocol) {
        ProtocolType.OpenAI_ChatCompletions, ProtocolType.Generic_OpenAI_Compat,
        ProtocolType.Mistral_Chat, ProtocolType.Moonshot_Kimi, ProtocolType.Qwen_DashScope,
        ProtocolType.Zhipu_GLM, ProtocolType.Doubao_ByteDance, ProtocolType.Baichuan, ProtocolType.DeepSeek -> mimeType in CHAT_AUDIO
        ProtocolType.Google_Gemini -> mimeType in GEMINI_AUDIO
        ProtocolType.Google_VertexAI -> mimeType in VERTEX_AUDIO
        else -> false
    }

    fun supportsVideo(mimeType: String): Boolean = capabilities.videoUnderstanding && when (protocol) {
        ProtocolType.Google_Gemini -> mimeType in GEMINI_VIDEO
        ProtocolType.Google_VertexAI -> mimeType in VERTEX_VIDEO
        else -> false
    }

    companion object {
        fun capabilities(model: ModelInfo?): ModelCapabilities {
            val flags = model?.takeIf { it.enabled }?.capabilities.orEmpty().map { it.lowercase() }.toSet()
            return ModelCapabilities(vision = "vision" in flags, audioInput = "audioinput" in flags,
                videoUnderstanding = "videounderstanding" in flags)
        }
        private val CHAT_AUDIO = setOf("audio/wav", "audio/x-wav", "audio/mp3", "audio/mpeg")
        private val GEMINI_AUDIO = setOf("audio/wav", "audio/mp3", "audio/aiff", "audio/aac", "audio/ogg",
            "audio/flac", "audio/mpeg", "audio/m4a", "audio/l16", "audio/opus", "audio/alaw", "audio/mulaw", "audio/webm")
        private val VERTEX_AUDIO = setOf("audio/x-aac", "audio/flac", "audio/mp3", "audio/m4a", "audio/mpeg",
            "audio/mpga", "audio/mp4", "audio/ogg", "audio/pcm", "audio/wav", "audio/webm")
        private val GEMINI_VIDEO = setOf("video/mp4", "video/mpeg", "video/quicktime", "video/avi", "video/x-flv",
            "video/mpg", "video/webm", "video/wmv", "video/3gpp")
        private val VERTEX_VIDEO = setOf("video/x-flv", "video/quicktime", "video/mpeg", "video/mpegs", "video/mpg",
            "video/mp4", "video/webm", "video/wmv", "video/3gpp")
    }
}
