package com.promenar.nexara.onboarding

import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole

/** 只接受生成链完成最终落盘后的助手成功态，流式分片本身永远不能完成首次引导。 */
fun isSuccessfulOnboardingAssistant(message: Message): Boolean =
    message.role == MessageRole.ASSISTANT &&
        message.status == "success" &&
        message.content.isNotBlank() &&
        !message.isError
