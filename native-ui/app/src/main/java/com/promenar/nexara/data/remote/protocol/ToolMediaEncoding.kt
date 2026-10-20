package com.promenar.nexara.data.remote.protocol

internal fun ProtocolMessage.hasMediaPayload(): Boolean =
    !imageUrls.isNullOrEmpty() || !audioData.isNullOrEmpty() || !videoData.isNullOrEmpty()

internal fun ProtocolMessage.mediaSourceText(): String =
    "以下为 view_media 加载的 " + mediaPaths?.takeIf { it.isNotEmpty() }?.joinToString("、").orEmpty()
        .ifEmpty { "媒体" }

internal fun ProtocolMessage.mediaResultText(
    protocolLabel: String,
    unsupportedImages: List<ImageInput> = emptyList(),
    unsupportedAudio: List<AudioInput> = emptyList(),
    unsupportedVideo: List<VideoInput> = emptyList(),
): String = buildList {
    if (content.isNotEmpty()) add(content)
    if (!mediaPaths.isNullOrEmpty()) add(mediaSourceText())
    fun unsupported(kind: String, mimeTypes: List<String>) {
        if (mimeTypes.isNotEmpty()) {
            add("当前协议（$protocolLabel）不支持${kind}输入，未发送${kind}数据（MIME：${mimeTypes.distinct().joinToString("、")}）。")
        }
    }
    unsupported("图片", unsupportedImages.map { it.mimeType })
    unsupported("音频", unsupportedAudio.map { it.mimeType })
    unsupported("视频", unsupportedVideo.map { it.mimeType })
}.joinToString("\n")

internal fun chatAudioFormat(mimeType: String): String? = when (mimeType.substringBefore(';').trim().lowercase()) {
    "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
    "audio/mp3", "audio/mpeg", "audio/x-mp3", "audio/mpga" -> "mp3"
    else -> null
}

/** 工具结果连续出现时，媒体只能在整组结果之后补入，不能拆开 assistant/tool 配对。 */
internal fun appendToolGroupMedia(
    messages: List<ProtocolMessage>,
    transformTool: (ProtocolMessage) -> ProtocolMessage,
    fallback: (List<ProtocolMessage>) -> ProtocolMessage?,
): List<ProtocolMessage> = buildList {
    var index = 0
    while (index < messages.size) {
        if (messages[index].role != "tool") {
            add(messages[index++])
            continue
        }
        val start = index
        while (index < messages.size && messages[index].role == "tool") index++
        val group = messages.subList(start, index)
        addAll(group.map(transformTool))
        fallback(group)?.let(::add)
    }
}

internal fun chatMessagesWithToolMedia(messages: List<ProtocolMessage>): List<ProtocolMessage> = appendToolGroupMedia(
    messages,
    transformTool = { message ->
        if (!message.hasMediaPayload() && message.mediaPaths.isNullOrEmpty()) message
        else message.copy(
            content = message.mediaResultText(
                "Chat Completions",
                unsupportedAudio = message.audioData.orEmpty().filter { chatAudioFormat(it.mimeType) == null },
                unsupportedVideo = message.videoData.orEmpty(),
            ),
            imageUrls = null,
            audioData = null,
            videoData = null,
        )
    },
    fallback = { group ->
        val media = group.filter { it.hasMediaPayload() }
        if (media.isEmpty()) null
        else ProtocolMessage(
            role = "user",
            content = media.joinToString("\n") { it.mediaSourceText() },
            imageUrls = media.flatMap { it.imageUrls.orEmpty() }.ifEmpty { null },
            audioData = media.flatMap { it.audioData.orEmpty() }.filter { chatAudioFormat(it.mimeType) != null }.ifEmpty { null },
        )
    },
).map { message ->
    val unsupportedAudio = message.audioData.orEmpty().filter { chatAudioFormat(it.mimeType) == null }
    if (unsupportedAudio.isEmpty() && message.videoData.isNullOrEmpty()) message
    else message.copy(
        content = message.mediaResultText(
            "Chat Completions",
            unsupportedAudio = unsupportedAudio,
            unsupportedVideo = message.videoData.orEmpty(),
        ),
        audioData = message.audioData?.filter { chatAudioFormat(it.mimeType) != null }?.ifEmpty { null },
        videoData = null,
    )
}

/** 本地引擎仅接收字符串，不能把媒体标记当作已传入媒体字节。 */
internal fun localMessagesWithToolMedia(messages: List<ProtocolMessage>): List<ProtocolMessage> {
    val grouped = appendToolGroupMedia(
        messages,
        transformTool = { message -> message.copy(imageUrls = null, audioData = null, videoData = null) },
        fallback = { group ->
            val media = group.filter { it.hasMediaPayload() || !it.mediaPaths.isNullOrEmpty() }
            if (media.isEmpty()) null
            else ProtocolMessage(
                role = "user",
                content = media.joinToString("\n") { message ->
                    message.copy(content = message.mediaSourceText(), mediaPaths = null).mediaResultText(
                        "本地文本推理引擎",
                        unsupportedImages = message.imageUrls.orEmpty(),
                        unsupportedAudio = message.audioData.orEmpty(),
                        unsupportedVideo = message.videoData.orEmpty(),
                    )
                },
            )
        },
    )
    return grouped.map { message ->
        if (!message.hasMediaPayload()) message
        else message.copy(
            content = message.mediaResultText(
                "本地文本推理引擎",
                unsupportedImages = message.imageUrls.orEmpty(),
                unsupportedAudio = message.audioData.orEmpty(),
                unsupportedVideo = message.videoData.orEmpty(),
            ),
            imageUrls = null,
            audioData = null,
            videoData = null,
        )
    }
}
