package com.promenar.infinity.domain.trigger

/** 同一个完成序号只允许等待工具或系统通知之一确认；租约本身不代表已送达。 */
interface SystemTurnDelivery {
    suspend fun claim(): Claim?

    interface Claim {
        suspend fun confirm()
        /** 仅在消息已持久化或已通过持久标记去重后调用；写水位失败也不能重新开放该序号。 */
        suspend fun confirmPersistedMessage() = confirm()
        fun release()
    }
}
