package com.promenar.infinity.domain.tool

/** 主机永久标识与连接身份摘要共同界定一次 SSH 授权的来源。 */
data class SshExecutionIdentity(val hostId: String, val digest: String)
