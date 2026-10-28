package com.promenar.infinity.domain.media

import kotlinx.serialization.Serializable

@Serializable
enum class MediaViewMode { ORIGINAL, KEYFRAMES }

/** 媒体查看描述只保存内容身份与查看方式，不保存媒体字节。 */
@Serializable
data class ViewedMedia(
    val ref: MediaRef,
    val mode: MediaViewMode = MediaViewMode.ORIGINAL,
    val frameTimestampsSeconds: List<Double> = emptyList(),
)
