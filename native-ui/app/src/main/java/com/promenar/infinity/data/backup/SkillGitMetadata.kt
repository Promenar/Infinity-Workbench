package com.promenar.infinity.data.backup

internal object SkillGitMetadata {
    // 只移除 URL authority 中的用户信息，保留 SCP 风格的 SSH 用户名与地址。
    private val urlUserInfo = Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/]*@")
    private val remoteSection = Regex("(?i)\\[\\s*remote\\s+\"((?:\\\\.|[^\"\\\\])*)\"\\s*]\\s*(?:[#;].*)?")
    private val commitPattern = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

    fun sanitizeUrls(value: String): String = urlUserInfo.replace(value) { "${it.groupValues[1]}" }

    fun isConfigPath(path: String): Boolean = path.split('/').let {
        ".git" in it.dropLast(1) && (it.last() == "config" || it.last() == "config.worktree")
    }

    fun sanitizeConfig(value: String): String = logicalLines(value).joinToString("\n", postfix = "\n") { line ->
        val key = line.substringBefore('=').trim()
        if ('=' in line && Regex("[A-Za-z][A-Za-z0-9-]*").matches(key)) {
            val safe = sanitizeUrls(parseValue(line.substringAfter('=')))
            line.substringBefore('=') + "= \"" + safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\b", "\\b") + "\""
        } else sanitizeUrls(line)
    }

    fun parse(relativePath: String, read: (String) -> String?): SkippedSkillGitRepository =
        parseLines(relativePath) { name, consume -> consume(read(name)?.lineSequence() ?: emptySequence()) }

    fun parseLines(relativePath: String, read: (String, (Sequence<String>) -> Unit) -> Unit): SkippedSkillGitRepository {
        var remote: String? = null
        val remotes = mutableListOf<SkillGitRemote>()
        var metadataBytes = 0L
        read("config") { lines -> logicalLines(lines).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith('[')) {
                remote = remoteSection.matchEntire(trimmed)?.groupValues?.get(1)?.let(::unescape)
            } else if (remote != null) {
                val key = trimmed.substringBefore('=').trim()
                if ('=' in trimmed && (key.equals("url", true) || key.equals("pushurl", true))) {
                    val value = parseValue(trimmed.substringAfter('='))
                    val safeUrl = sanitizeUrls(value)
                    metadataBytes += remote!!.toByteArray().size + safeUrl.toByteArray().size + 64L
                    if (metadataBytes > BackupPackageLimits.MAX_MANIFEST_BYTES) {
                        throw BackupValidationException("Git 远端信息超过备份清单限制")
                    }
                    remotes += SkillGitRemote(remote!!, safeUrl, key.equals("pushurl", true))
                }
            }
        } }
        fun singleLine(name: String): String? {
            var result: String? = null
            read(name) { lines ->
                val nonBlank = lines.filter { it.isNotBlank() }.take(2).toList()
                if (nonBlank.size == 1) result = nonBlank.single().trim()
            }
            return result
        }
        val head = singleLine("HEAD")
        val ref = head?.takeIf { it.startsWith("ref:") }?.substringAfter(':')?.trim()
        val branch = ref?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
        val safeRef = ref?.takeIf {
            runCatching { SkillBackupPaths.parts(it) }.isSuccess && it.startsWith("refs/heads/")
        }
        val loose = safeRef?.let(::singleLine)?.takeIf(commitPattern::matches)
        var packed: String? = null
        if (safeRef != null && loose == null) {
            read("packed-refs") { lines ->
                packed = lines.filterNot { it.startsWith('#') || it.startsWith('^') }
                    .map { it.trim().split(Regex("\\s+"), limit = 2) }
                    .firstOrNull { it.size == 2 && it[1] == safeRef && commitPattern.matches(it[0]) }?.first()
            }
        }
        val commit = loose ?: packed ?: head?.takeIf(commitPattern::matches)
        return SkippedSkillGitRepository(relativePath, remotes, branch, commit?.lowercase())
    }

    private fun logicalLines(config: String): Sequence<String> = logicalLines(config.lineSequence())

    private fun logicalLines(lines: Sequence<String>): Sequence<String> = sequence {
        val pending = StringBuilder()
        var quoted = false
        for (line in lines) {
            if (pending.length.toLong() + line.length > BackupPackageLimits.MAX_MANIFEST_BYTES) {
                throw BackupValidationException("Git 元数据单行超过备份清单限制")
            }
            pending.append(line)
            var escaped = false
            var comment = false
            for (character in line) {
                when {
                    comment -> Unit
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = !quoted
                    !quoted && (character == '#' || character == ';') -> comment = true
                }
            }
            if (!comment && escaped) pending.setLength(pending.length - 1) else {
                yield(pending.toString())
                pending.setLength(0)
                quoted = false
            }
        }
        if (pending.isNotEmpty()) yield(pending.toString())
    }

    private fun parseValue(raw: String): String {
        val value = StringBuilder()
        var significantLength = 0
        var quoted = false
        var escaped = false
        for (character in raw.trimStart()) {
            when {
                escaped -> {
                    value.append(when (character) { 'n' -> '\n'; 't' -> '\t'; 'b' -> '\b'; else -> character })
                    significantLength = value.length
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && (character == '#' || character == ';') -> break
                else -> {
                    value.append(character)
                    if (quoted || !character.isWhitespace()) significantLength = value.length
                }
            }
        }
        return value.substring(0, significantLength)
    }

    private fun unescape(raw: String): String = parseValue("\"$raw\"")
}
