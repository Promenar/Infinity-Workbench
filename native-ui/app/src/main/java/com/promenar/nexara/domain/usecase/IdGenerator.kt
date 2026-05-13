package com.promenar.nexara.domain.usecase

import java.util.UUID

object IdGenerator {
    fun agent(): String = "agent_${System.currentTimeMillis()}"
    fun session(): String = "session_${System.currentTimeMillis()}"
    fun message(prefix: String = "msg"): String = "${prefix}_${System.currentTimeMillis()}"
    fun document(): String = "doc_${System.currentTimeMillis()}"
    fun folder(): String = "folder_${System.currentTimeMillis()}"
    fun uuid(): String = UUID.randomUUID().toString()
    fun skill(): String = "skill_${System.currentTimeMillis()}"
}
