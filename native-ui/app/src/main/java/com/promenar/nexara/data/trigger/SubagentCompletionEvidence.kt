package com.promenar.nexara.data.trigger

import com.promenar.nexara.data.model.Message

/** 只有完全没有执行证据的助手占位，才可在恢复时作为未启动的一轮复用。 */
internal fun Message.isUnstartedCompletionPlaceholder(): Boolean =
    content.isEmpty() && reasoning.isNullOrEmpty() && thoughtSignature.isNullOrEmpty() &&
        status == null && tokens == null && !isError && errorMessage.isNullOrEmpty() &&
        toolCalls.isNullOrEmpty() && toolResults.isNullOrEmpty() && executionSteps.isNullOrEmpty() &&
        pendingApprovalToolIds.isNullOrEmpty() && images.isNullOrEmpty() && userImages.isNullOrEmpty() &&
        planningTask == null && loopCount == 0
