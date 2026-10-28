package com.promenar.infinity.ui.chat

/** 工具已返回任务号时，仍以持有的实时通道决定面板可见性。 */
internal fun activeToolOutputs(invocations: List<ToolInvocationPresentation>, keys: Set<String>): List<ToolInvocationPresentation> =
    invocations.filter { it.call?.id?.substringAfter("::") in keys }
