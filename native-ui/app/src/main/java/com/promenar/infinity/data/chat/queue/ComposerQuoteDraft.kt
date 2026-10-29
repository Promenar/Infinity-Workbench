package com.promenar.infinity.data.chat.queue

import com.promenar.infinity.data.model.MessageQuote
import java.util.UUID

/** 输入区上方的一张引用卡；批注可以留空，留空的卡在发送时丢弃。 */
data class ComposerQuoteDraft(
    val id: String,
    val sourceMessageId: String,
    val text: String,
    val annotation: String = "",
)

/** 引用卡的发送规则与折叠计数。 */
object ComposerQuotes {
    /** 引用卡超过这个数量时折叠成一行摘要。 */
    const val COLLAPSE_ABOVE = 2

    /** 新建一张引用卡；选区首尾空白去掉，空选区返回 null。 */
    fun draft(sourceMessageId: String, selectedText: String, id: String = UUID.randomUUID().toString()): ComposerQuoteDraft? {
        val text = selectedText.trim()
        if (text.isEmpty() || sourceMessageId.isBlank()) return null
        return ComposerQuoteDraft(id = id, sourceMessageId = sourceMessageId, text = text)
    }

    /** 写了批注的引用卡才进入消息，批注首尾空白去掉。 */
    fun toMessageQuotes(drafts: List<ComposerQuoteDraft>): List<MessageQuote> = drafts
        .filter { it.annotation.isNotBlank() }
        .map { MessageQuote(sourceMessageId = it.sourceMessageId, text = it.text, annotation = it.annotation.trim()) }

    /** 已发出的引用回到输入区（发送失败、编辑排队消息）时还原成引用卡。 */
    fun fromMessageQuotes(quotes: List<MessageQuote>): List<ComposerQuoteDraft> = quotes.map {
        ComposerQuoteDraft(id = UUID.randomUUID().toString(), sourceMessageId = it.sourceMessageId, text = it.text, annotation = it.annotation)
    }

    fun annotatedCount(drafts: List<ComposerQuoteDraft>): Int = drafts.count { it.annotation.isNotBlank() }

    /** 发送按钮可用：主输入框或附件有内容，或至少有一张写了批注的引用卡。 */
    fun hasSendableDraft(text: String, hasImages: Boolean, hasDocuments: Boolean, quotes: List<ComposerQuoteDraft>): Boolean =
        text.isNotBlank() || hasImages || hasDocuments || annotatedCount(quotes) > 0

    fun collapsible(drafts: List<ComposerQuoteDraft>): Boolean = drafts.size > COLLAPSE_ABOVE

    /** 已在输入区的引用卡与恢复回来的引用卡合并：同一来源同一引文只保留一张，已有的批注优先。 */
    fun merge(restored: List<ComposerQuoteDraft>, current: List<ComposerQuoteDraft>): List<ComposerQuoteDraft> {
        val seen = current.mapTo(mutableSetOf()) { it.sourceMessageId to it.text }
        return restored.filter { seen.add(it.sourceMessageId to it.text) } + current
    }
}
