package com.promenar.infinity.data.model

import kotlinx.serialization.Serializable

/**
 * 用户消息附带的一段引用：从同一会话某条消息正文中选出的原文，以及用户对它的批注。
 * 只有写了批注的引用才会进入消息；发给模型时序列化为“引文块 + 批注”。
 */
@Serializable
data class MessageQuote(
    /** 来源消息 ID，用户气泡点击引文时据此定位。 */
    val sourceMessageId: String,
    /** 选中的原文。 */
    val text: String,
    /** 用户批注，进入消息的引用一定非空。 */
    val annotation: String,
)
