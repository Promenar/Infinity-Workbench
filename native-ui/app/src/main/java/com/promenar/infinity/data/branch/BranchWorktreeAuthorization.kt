package com.promenar.infinity.data.branch

import kotlinx.serialization.Serializable

/** 方案批准时冻结命令全文及工作区逻辑身份，排队、重启或父会话设置变化都不能改写执行目标。 */
@Serializable
data class BranchWorktreeAuthorization(
    val workspaceId: String,
    val workspaceKind: String,
    val workspaceLocation: String,
    val workingDirectory: String,
    val command: String,
    val mountId: String? = null,
    val mountSource: String? = null,
) {
    companion object { const val MESSAGE_NAME = "branch_worktree_authorization" }
}
