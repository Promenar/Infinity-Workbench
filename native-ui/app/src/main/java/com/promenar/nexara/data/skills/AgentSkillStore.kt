package com.promenar.nexara.data.skills

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已安装技能：元数据、附带文件（相对路径，不含 SKILL.md）与启用状态。 */
data class InstalledAgentSkill(
    val metadata: AgentSkillMetadata,
    val files: List<String>,
    val enabled: Boolean,
)

class AgentSkillConflictException(val name: String) : IllegalStateException("技能 $name 已存在")

/**
 * 技能包的文件系统存储，位于应用私有目录。
 *
 * 安装先写入同级暂存目录并完整校验，再以目录重命名切换，失败不留下半个技能。
 * 读取接口同步且基于内存索引，供工具解析在生成协程中直接调用。
 */
class AgentSkillStore(
    private val rootDir: File,
    private val settings: SharedPreferences,
) {
    private val lock = Any()
    private val _skills = MutableStateFlow<List<InstalledAgentSkill>>(emptyList())
    @Volatile private var loaded = false

    val skills: StateFlow<List<InstalledAgentSkill>>
        get() {
            ensureLoaded()
            return _skills.asStateFlow()
        }

    fun list(): List<InstalledAgentSkill> {
        ensureLoaded()
        return _skills.value
    }

    fun enabledSkills(): List<AgentSkillMetadata> = list().filter { it.enabled }.map { it.metadata }

    fun find(name: String): InstalledAgentSkill? = list().firstOrNull { it.metadata.name == name }

    fun setEnabled(name: String, enabled: Boolean) = synchronized(lock) {
        val current = enabledNames().toMutableSet()
        if (enabled) current += name else current -= name
        settings.edit { putStringSet(ENABLED_KEY, current) }
        reload()
    }

    /** 读取技能正文；技能不存在或 SKILL.md 已损坏时返回 null。 */
    fun readDocument(name: String): ParsedSkillDocument? {
        if (find(name) == null) return null
        val file = File(skillDir(name), SKILL_FILE)
        return runCatching { SkillMarkdownParser.parse(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 读取技能附带的文本文件；路径越界、过大或非文本时抛出 [AgentSkillFormatException]。 */
    fun readFile(name: String, path: String): String {
        val skill = find(name) ?: throw AgentSkillFormatException("技能不存在：$name")
        val relative = normalizeRelativePath(path)
        if (relative != SKILL_FILE && relative !in skill.files) {
            throw AgentSkillFormatException("技能 $name 中没有文件：$relative")
        }
        val file = File(skillDir(name), relative)
        if (file.length() > MAX_READ_BYTES) {
            throw AgentSkillFormatException("文件超过 ${MAX_READ_BYTES / 1024} KiB 读取上限：$relative")
        }
        return decodeText(file.readBytes()) ?: throw AgentSkillFormatException("不是 UTF-8 文本文件：$relative")
    }

    fun installMarkdown(text: String, overwrite: Boolean = false): AgentSkillMetadata {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw AgentSkillFormatException("SKILL.md 超过 ${MAX_FILE_BYTES / 1024} KiB")
        return install(mapOf(SKILL_FILE to bytes), overwrite, previousName = null)
    }

    fun installZip(input: InputStream, overwrite: Boolean = false): AgentSkillMetadata {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val rawName = entry.name.replace('\\', '/')
                if (rawName.startsWith("__MACOSX/") || rawName.substringAfterLast('/') == ".DS_Store") continue
                val path = normalizeRelativePath(rawName)
                if (entries.size >= MAX_FILES) throw AgentSkillFormatException("技能包文件数超过 $MAX_FILES")
                val bytes = readBounded(zip, MAX_FILE_BYTES) ?: throw AgentSkillFormatException(
                    "文件超过 ${MAX_FILE_BYTES / 1024} KiB：$path",
                )
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw AgentSkillFormatException("技能包解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MiB")
                if (entries.put(path, bytes) != null) throw AgentSkillFormatException("技能包包含重复路径：$path")
            }
        }
        return install(stripSingleTopLevelDirectory(entries), overwrite, previousName = null)
    }

    /** 应用内新建或编辑；改名时保留原技能的附带文件。 */
    fun save(previousName: String?, metadata: AgentSkillMetadata, body: String): AgentSkillMetadata {
        val text = SkillMarkdownParser.render(metadata, body)
        val files = linkedMapOf(SKILL_FILE to text.toByteArray(Charsets.UTF_8))
        if (previousName != null) {
            val existing = find(previousName) ?: throw AgentSkillFormatException("技能不存在：$previousName")
            existing.files.forEach { relative -> files[relative] = File(skillDir(previousName), relative).readBytes() }
        }
        return install(files, overwrite = previousName == metadata.name, previousName = previousName)
    }

    fun delete(name: String) = synchronized(lock) {
        val dir = skillDir(name)
        if (dir.exists()) {
            val trash = File(rootDir, ".trash-${UUID.randomUUID()}")
            if (!dir.renameTo(trash)) throw IllegalStateException("无法删除技能 $name")
            trash.deleteRecursively()
        }
        settings.edit { putStringSet(ENABLED_KEY, enabledNames() - name) }
        reload()
    }

    private fun install(
        files: Map<String, ByteArray>,
        overwrite: Boolean,
        previousName: String?,
    ): AgentSkillMetadata = synchronized(lock) {
        val skillBytes = files[SKILL_FILE] ?: throw AgentSkillFormatException("技能包缺少 SKILL.md")
        val text = decodeText(skillBytes) ?: throw AgentSkillFormatException("SKILL.md 不是 UTF-8 文本")
        val metadata = SkillMarkdownParser.parse(text).metadata
        val target = skillDir(metadata.name)
        val renaming = previousName != null && previousName != metadata.name
        if (target.exists() && !overwrite) throw AgentSkillConflictException(metadata.name)

        rootDir.mkdirs()
        val staging = File(rootDir, ".staging-${UUID.randomUUID()}")
        try {
            files.forEach { (relative, bytes) ->
                val out = File(staging, normalizeRelativePath(relative))
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
            var replaced: File? = null
            if (target.exists()) {
                replaced = File(rootDir, ".trash-${UUID.randomUUID()}")
                if (!target.renameTo(replaced)) throw IllegalStateException("无法替换技能 ${metadata.name}")
            }
            if (!staging.renameTo(target)) {
                replaced?.renameTo(target)
                throw IllegalStateException("无法安装技能 ${metadata.name}")
            }
            replaced?.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }

        val enabled = enabledNames().toMutableSet()
        val wasEnabled = previousName?.let { it in enabled } ?: true
        if (renaming) {
            skillDir(previousName!!).deleteRecursively()
            enabled -= previousName
        }
        if (wasEnabled) enabled += metadata.name
        settings.edit { putStringSet(ENABLED_KEY, enabled) }
        reload()
        metadata
    }

    private fun ensureLoaded() {
        if (!loaded) synchronized(lock) { if (!loaded) reload() }
    }

    private fun reload() {
        val enabled = enabledNames()
        val skills = rootDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val document = runCatching {
                    SkillMarkdownParser.parse(File(dir, SKILL_FILE).readText(Charsets.UTF_8))
                }.getOrNull() ?: return@mapNotNull null
                // 目录名与 frontmatter 名称不一致的包视为损坏，不提供给模型。
                if (document.metadata.name != dir.name) return@mapNotNull null
                InstalledAgentSkill(
                    metadata = document.metadata,
                    files = dir.walkTopDown()
                        .filter { it.isFile }
                        .map { it.relativeTo(dir).invariantSeparatorsPath }
                        .filter { it != SKILL_FILE }
                        .sorted()
                        .toList(),
                    enabled = document.metadata.name in enabled,
                )
            }
            .sortedBy { it.metadata.name }
        _skills.value = skills
        loaded = true
    }

    private fun enabledNames(): Set<String> = settings.getStringSet(ENABLED_KEY, null)?.toSet().orEmpty()

    private fun skillDir(name: String): File {
        SkillMarkdownParser.validateName(name)
        return File(rootDir, name)
    }

    companion object {
        const val ENABLED_KEY = "agent_skills_enabled"
        const val SKILL_FILE = "SKILL.md"
        const val MAX_FILES = 200
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_TOTAL_BYTES = 5L * 1024 * 1024
        const val MAX_READ_BYTES = 256L * 1024

        /** 归一化包内相对路径并拒绝越界、绝对路径与保留名。 */
        fun normalizeRelativePath(raw: String): String {
            val path = raw.replace('\\', '/').trim()
            if (path.isEmpty() || path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path) || '\u0000' in path) {
                throw AgentSkillFormatException("非法路径：$raw")
            }
            val segments = path.split('/').filter(String::isNotEmpty)
            if (segments.isEmpty() || segments.any { it == "." || it == ".." || it.startsWith(".nexara", ignoreCase = true) }) {
                throw AgentSkillFormatException("非法路径：$raw")
            }
            return segments.joinToString("/")
        }

        internal fun stripSingleTopLevelDirectory(entries: Map<String, ByteArray>): Map<String, ByteArray> {
            if (SKILL_FILE in entries) return entries
            val tops = entries.keys.map { it.substringBefore('/') }.toSet()
            val top = tops.singleOrNull()
            if (top == null || "$top/$SKILL_FILE" !in entries) {
                throw AgentSkillFormatException("SKILL.md 必须位于压缩包根部或唯一的顶层目录中")
            }
            return entries.mapKeys { (path, _) -> path.removePrefix("$top/") }
        }

        fun decodeText(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .removePrefix("\uFEFF")
            }.getOrNull()
        }

        private fun readBounded(input: InputStream, limit: Int): ByteArray? {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (buffer.size() + read > limit) return null
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }
}
