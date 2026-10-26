package com.promenar.nexara.data.memory

/** 只拒绝可辨认的凭据形态，错误信息不回显原文。 */
object LongTermMemoryValidation {
    const val MAX_MEMORIES = 200
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun validateId(id: String) {
        if (!uuidPattern.matches(id)) throw LongTermMemoryException(LongTermMemoryFailure.INVALID_RESTORE)
    }
    private val credentialPatterns = listOf(
        Regex("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
        Regex("""\b(?:sk|pk)-(?:[A-Za-z0-9_-]{16,})\b"""),
        Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[A-Za-z0-9_-]{25,}|AKIA[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{10,})\b"""),
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/-]{8,}=*"""),
        Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b"""),
        Regex("""(?i)(?:api[ _-]?key|access[ _-]?token|refresh[ _-]?token|auth[ _-]?token|token|password|passwd|secret|client[ _-]?secret|密码|口令|令牌|密钥)[\s"']*(?:[:=：]|是|为)[\s"']*\S+"""),
        Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s/:@]+:[^\s/@]+@"""),
    )

    fun validate(title: String, content: String): Pair<String, String> {
        val cleanTitle = title.trim()
        val cleanContent = content.trim()
        if (cleanTitle.length !in 1..80) throw LongTermMemoryException(LongTermMemoryFailure.INVALID_TITLE)
        if (cleanContent.length !in 1..2000) throw LongTermMemoryException(LongTermMemoryFailure.INVALID_CONTENT)
        if (credentialPatterns.any { it.containsMatchIn(cleanTitle) || it.containsMatchIn(cleanContent) || it.containsMatchIn("$cleanTitle: $cleanContent") }) {
            throw LongTermMemoryException(LongTermMemoryFailure.CREDENTIALS)
        }
        return cleanTitle to cleanContent
    }
}
