package com.promenar.nexara.domain.tool

import com.promenar.nexara.domain.model.ExecutionMode

enum class ToolRisk {
    SAFE_READ,
    FILE_WRITE,
    PATCH,
    DELETE,
    PROCESS,
    SCRIPT,
    EXTERNAL_WRITE,
    UNKNOWN,
}

object ToolExecutionPolicy {
    fun requiresApproval(mode: ExecutionMode, risk: ToolRisk): Boolean = when (mode) {
        ExecutionMode.AUTO -> false
        ExecutionMode.MANUAL -> true
        ExecutionMode.SEMI -> risk != ToolRisk.SAFE_READ
    }
}
