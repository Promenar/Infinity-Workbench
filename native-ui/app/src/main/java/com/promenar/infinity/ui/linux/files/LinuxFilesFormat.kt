package com.promenar.infinity.ui.linux.files

import androidx.annotation.StringRes
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxFileEntry
import com.promenar.infinity.domain.linux.LinuxFileErrorCode
import com.promenar.infinity.domain.linux.LinuxFileType
import com.promenar.infinity.domain.linux.LinuxLineEnding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Linux 主目录；浏览页路径行以它（`~`）或整个根目录（`/`）为起点。 */
const val LINUX_HOME_PATH = "/root"

// ── 路径 ──

/** 规范化容器内绝对路径：合并多余斜杠，解析 `.` 与 `..`，越过根时停在根。 */
internal fun normalizeLinuxPath(path: String): String {
    val parts = ArrayDeque<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(segment)
        }
    }
    return "/" + parts.joinToString("/")
}

/** 上一级目录；根目录返回 null。 */
internal fun linuxParentPath(path: String): String? =
    if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }

internal fun linuxChildPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else "$parent/$name"

/** 符号链接目标按链接所在目录解析为绝对路径（不跟随中间链接，由数据层负责）。 */
internal fun resolveLinuxLinkTarget(linkPath: String, target: String): String =
    if (target.startsWith('/')) normalizeLinuxPath(target)
    else normalizeLinuxPath((linuxParentPath(linkPath) ?: "/") + "/" + target)

/** `/workspace` 与 `/mnt/<别名>` 由运行时在启动进程时绑定，不在 rootfs 中，文件浏览无法进入。 */
internal fun isLinuxRuntimeBinding(path: String): Boolean {
    val normalized = normalizeLinuxPath(path)
    return normalized == "/workspace" || normalized.startsWith("/workspace/") || normalized.startsWith("/mnt/")
}

/** 面包屑：首段为根 `/`，其后逐级；每段的 [LinuxPathSegment.path] 是点击后进入的目录。 */
internal data class LinuxPathSegment(val name: String, val path: String)

internal fun linuxPathSegments(path: String): List<LinuxPathSegment> {
    val names = normalizeLinuxPath(path).split('/').filter(String::isNotEmpty)
    return listOf(LinuxPathSegment("/", "/")) +
        names.mapIndexed { index, name -> LinuxPathSegment(name, "/" + names.take(index + 1).joinToString("/")) }
}

internal fun isUnderLinuxHome(path: String): Boolean =
    path == LINUX_HOME_PATH || path.startsWith("$LINUX_HOME_PATH/")

/** 路径行：主目录内相对 `~`，其余相对 `/`；[trail] 不含起点本身。 */
internal data class LinuxCrumbs(val underHome: Boolean, val trail: List<LinuxPathSegment>)

internal fun linuxCrumbs(path: String): LinuxCrumbs {
    val normalized = normalizeLinuxPath(path)
    val underHome = isUnderLinuxHome(normalized)
    val rootDepth = if (underHome) linuxPathSegments(LINUX_HOME_PATH).size else 1
    return LinuxCrumbs(underHome, linuxPathSegments(normalized).drop(rootDepth))
}

/** 当前目录内按名称筛选，不区分大小写；空白查询保留全部条目。 */
internal fun filterLinuxEntriesByName(entries: List<LinuxFileEntry>, query: String): List<LinuxFileEntry> {
    val needle = query.trim()
    return if (needle.isEmpty()) entries else entries.filter { it.name.contains(needle, ignoreCase = true) }
}

/** 供 `sh -c` 使用的单引号转义；纯安全字符原样返回。 */
internal fun shellQuote(value: String): String =
    if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "._-/+=:@%" }) value
    else "'" + value.replace("'", "'\\''") + "'"

// ── 权限 ──

private const val SPECIAL_BITS = 0b111_000_000_000

/** 类 `ls -l` 的十位权限串，含 setuid/setgid/sticky 的 s/S/t/T。 */
internal fun formatLinuxPermissions(type: LinuxFileType, mode: Int): String = buildString {
    append(
        when (type) {
            LinuxFileType.DIRECTORY -> 'd'
            LinuxFileType.SYMLINK -> 'l'
            LinuxFileType.FILE -> '-'
            LinuxFileType.OTHER -> '?'
        },
    )
    val special = intArrayOf(0b100_000_000_000, 0b010_000_000_000, 0b001_000_000_000)
    for (group in 0..2) {
        val shift = (2 - group) * 3
        append(if (mode and (0b100 shl shift) != 0) 'r' else '-')
        append(if (mode and (0b010 shl shift) != 0) 'w' else '-')
        val execute = mode and (0b001 shl shift) != 0
        val hasSpecial = mode and special[group] != 0
        val (setChar, unsetChar) = if (group == 2) 't' to 'T' else 's' to 'S'
        append(
            when {
                hasSpecial && execute -> setChar
                hasSpecial -> unsetChar
                execute -> 'x'
                else -> '-'
            },
        )
    }
}

/** 八进制权限：没有特殊位时三位（`755`），有则四位（`4755`）。 */
internal fun formatLinuxOctalMode(mode: Int): String {
    val bits = mode and 0b111_111_111_111
    return if (bits and SPECIAL_BITS != 0) "%04o".format(bits) else "%03o".format(bits and 0b111_111_111)
}

/** 权限对话框中的一位：[group] 0 属主、1 属组、2 其他；[permission] 0 读、1 写、2 执行。 */
internal fun linuxPermissionBit(group: Int, permission: Int): Int = 1 shl ((2 - group) * 3 + (2 - permission))

internal fun toggleLinuxPermission(mode: Int, group: Int, permission: Int): Int =
    mode xor linuxPermissionBit(group, permission)

internal fun LinuxFileEntry.isExecutableFile(): Boolean =
    type == LinuxFileType.FILE && mode and 0b001_001_001 != 0

/** 列表中按“目录”对待的条目：真实目录与解析到目录的链接。 */
internal val LinuxFileEntry.opensAsDirectory: Boolean
    get() = type == LinuxFileType.DIRECTORY || (type == LinuxFileType.SYMLINK && targetIsDirectory)

// ── 大小与时间 ──

/** 二进制单位（1 KB = 1024 B），小数点固定为 `.`，与权限串等标识同样不随语言变化。 */
internal fun formatLinuxSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (value < 100) String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    else String.format(Locale.ROOT, "%.0f", value)
    return "$number ${units[unit]}"
}

/** 相对修改时间；界面按类型选文案。 */
internal sealed interface LinuxRelativeTime {
    data object JustNow : LinuxRelativeTime
    data class MinutesAgo(val minutes: Int) : LinuxRelativeTime
    data class HoursAgo(val hours: Int) : LinuxRelativeTime
    data object Yesterday : LinuxRelativeTime
    data class DaysAgo(val days: Int) : LinuxRelativeTime
    /** 一周以前：同年 `MM-dd`，跨年 `yyyy-MM-dd`。 */
    data class Date(val text: String) : LinuxRelativeTime
}

internal fun linuxRelativeTime(modifiedMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LinuxRelativeTime {
    val delta = nowMs - modifiedMs
    if (delta < 60_000) return LinuxRelativeTime.JustNow
    if (delta < 3_600_000) return LinuxRelativeTime.MinutesAgo((delta / 60_000).toInt())
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(modifiedMs).atZone(zone).toLocalDate()
    if (day == today) return LinuxRelativeTime.HoursAgo((delta / 3_600_000).toInt())
    val days = today.toEpochDay() - day.toEpochDay()
    return when {
        days == 1L -> LinuxRelativeTime.Yesterday
        days < 7 -> LinuxRelativeTime.DaysAgo(days.toInt())
        day.year == today.year -> LinuxRelativeTime.Date(day.format(DateTimeFormatter.ofPattern("MM-dd")))
        else -> LinuxRelativeTime.Date(day.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }
}

/** 属性页的修改时间：今天、昨天或完整日期，后接 `HH:mm`。 */
internal data class LinuxTimestamp(val day: LinuxTimestampDay, val date: String, val time: String)

internal enum class LinuxTimestampDay { TODAY, YESTERDAY, OTHER }

internal fun linuxTimestamp(modifiedMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LinuxTimestamp {
    val moment = Instant.ofEpochMilli(modifiedMs).atZone(zone)
    val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val day = when (moment.toLocalDate()) {
        today -> LinuxTimestampDay.TODAY
        today.minusDays(1) -> LinuxTimestampDay.YESTERDAY
        else -> LinuxTimestampDay.OTHER
    }
    return LinuxTimestamp(day, moment.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE), moment.format(DateTimeFormatter.ofPattern("HH:mm")))
}

// ── 排序 ──

enum class LinuxFileSort { NAME, MODIFIED, SIZE }

/**
 * 按名称时目录在前、名称不分大小写升序；按修改时间时新的在前；按大小时目录在前（按名称），文件大的在前。
 * [showHidden] 为 false 时去掉以 `.` 开头的条目。
 */
internal fun sortLinuxEntries(entries: List<LinuxFileEntry>, sort: LinuxFileSort, showHidden: Boolean): List<LinuxFileEntry> {
    val byName = compareBy<LinuxFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
    val comparator = when (sort) {
        LinuxFileSort.NAME -> compareByDescending<LinuxFileEntry> { it.opensAsDirectory }.then(byName)
        LinuxFileSort.MODIFIED -> compareByDescending<LinuxFileEntry> { it.modifiedMs }.then(byName)
        LinuxFileSort.SIZE -> compareByDescending<LinuxFileEntry> { it.opensAsDirectory }
            .thenByDescending { if (it.opensAsDirectory) 0L else it.size }
            .then(byName)
    }
    return entries.filter { showHidden || !it.isHidden }.sortedWith(comparator)
}

// ── 类型识别 ──

/** 文件内容的种类：决定属性页的类型文案与内容页的语法上色。 */
enum class LinuxFileKind(val syntaxName: String?) {
    SHELL("shell"),
    PYTHON("python"),
    JAVASCRIPT("javascript"),
    TYPESCRIPT("typescript"),
    C("c"),
    CPP("cpp"),
    GO("go"),
    RUST("rust"),
    JAVA("java"),
    KOTLIN("kotlin"),
    RUBY("ruby"),
    PERL("perl"),
    PHP("php"),
    JSON(null),
    YAML(null),
    MARKDOWN(null),
    CONFIG(null),
    TEXT(null),
    ARCHIVE(null),
    IMAGE(null),
    BINARY(null),
}

private val EXTENSION_KINDS = mapOf(
    "sh" to LinuxFileKind.SHELL, "bash" to LinuxFileKind.SHELL, "zsh" to LinuxFileKind.SHELL, "ash" to LinuxFileKind.SHELL,
    "py" to LinuxFileKind.PYTHON, "pyw" to LinuxFileKind.PYTHON,
    "js" to LinuxFileKind.JAVASCRIPT, "mjs" to LinuxFileKind.JAVASCRIPT, "cjs" to LinuxFileKind.JAVASCRIPT,
    "ts" to LinuxFileKind.TYPESCRIPT, "tsx" to LinuxFileKind.TYPESCRIPT,
    "c" to LinuxFileKind.C, "h" to LinuxFileKind.C,
    "cc" to LinuxFileKind.CPP, "cpp" to LinuxFileKind.CPP, "hpp" to LinuxFileKind.CPP, "cxx" to LinuxFileKind.CPP,
    "go" to LinuxFileKind.GO, "rs" to LinuxFileKind.RUST, "java" to LinuxFileKind.JAVA,
    "kt" to LinuxFileKind.KOTLIN, "kts" to LinuxFileKind.KOTLIN,
    "rb" to LinuxFileKind.RUBY, "pl" to LinuxFileKind.PERL, "php" to LinuxFileKind.PHP,
    "json" to LinuxFileKind.JSON,
    "yml" to LinuxFileKind.YAML, "yaml" to LinuxFileKind.YAML,
    "md" to LinuxFileKind.MARKDOWN, "markdown" to LinuxFileKind.MARKDOWN,
    "conf" to LinuxFileKind.CONFIG, "cfg" to LinuxFileKind.CONFIG, "ini" to LinuxFileKind.CONFIG, "toml" to LinuxFileKind.CONFIG,
    "txt" to LinuxFileKind.TEXT, "log" to LinuxFileKind.TEXT, "csv" to LinuxFileKind.TEXT,
    "gz" to LinuxFileKind.ARCHIVE, "tgz" to LinuxFileKind.ARCHIVE, "tar" to LinuxFileKind.ARCHIVE, "zip" to LinuxFileKind.ARCHIVE,
    "xz" to LinuxFileKind.ARCHIVE, "bz2" to LinuxFileKind.ARCHIVE, "zst" to LinuxFileKind.ARCHIVE, "apk" to LinuxFileKind.ARCHIVE,
    "png" to LinuxFileKind.IMAGE, "jpg" to LinuxFileKind.IMAGE, "jpeg" to LinuxFileKind.IMAGE, "gif" to LinuxFileKind.IMAGE,
    "webp" to LinuxFileKind.IMAGE,
    "bin" to LinuxFileKind.BINARY, "so" to LinuxFileKind.BINARY, "o" to LinuxFileKind.BINARY, "a" to LinuxFileKind.BINARY,
    "pyc" to LinuxFileKind.BINARY, "class" to LinuxFileKind.BINARY, "db" to LinuxFileKind.BINARY, "sqlite" to LinuxFileKind.BINARY,
    "pt" to LinuxFileKind.BINARY, "onnx" to LinuxFileKind.BINARY, "safetensors" to LinuxFileKind.BINARY, "gguf" to LinuxFileKind.BINARY,
)

private val SHEBANG_KINDS = listOf(
    "python" to LinuxFileKind.PYTHON, "node" to LinuxFileKind.JAVASCRIPT, "ruby" to LinuxFileKind.RUBY,
    "perl" to LinuxFileKind.PERL, "php" to LinuxFileKind.PHP, "sh" to LinuxFileKind.SHELL,
)

/** 只看文件名猜测；列表行用它标注“二进制”，不读取内容。 */
internal fun linuxKindByName(name: String): LinuxFileKind? {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty() || extension == name.lowercase().removePrefix(".")) {
        return when (name) {
            "Makefile", "Dockerfile", "Containerfile" -> LinuxFileKind.CONFIG
            ".bashrc", ".profile", ".ashrc", ".bash_profile", ".zshrc" -> LinuxFileKind.SHELL
            else -> null
        }
    }
    return EXTENSION_KINDS[extension]
}

/** 结合内容判断：二进制优先，其次扩展名，最后看 shebang。 */
internal fun linuxFileKind(name: String, isBinary: Boolean, firstLine: String?): LinuxFileKind {
    if (isBinary) return linuxKindByName(name)?.takeIf { it == LinuxFileKind.ARCHIVE || it == LinuxFileKind.IMAGE } ?: LinuxFileKind.BINARY
    linuxKindByName(name)?.takeUnless { it == LinuxFileKind.BINARY || it == LinuxFileKind.ARCHIVE || it == LinuxFileKind.IMAGE }?.let { return it }
    val shebang = firstLine?.takeIf { it.startsWith("#!") }?.substring(2)?.trim().orEmpty()
    if (shebang.isNotEmpty()) {
        val interpreter = shebang.split(' ', '\t').let { parts ->
            val program = parts.first().substringAfterLast('/')
            if (program == "env") parts.drop(1).firstOrNull { !it.startsWith("-") }.orEmpty() else program
        }
        SHEBANG_KINDS.firstOrNull { (prefix, _) -> interpreter.startsWith(prefix) || interpreter.endsWith(prefix) }?.let { return it.second }
    }
    return LinuxFileKind.TEXT
}

internal fun linuxLooksBinaryByName(name: String): Boolean = linuxKindByName(name) == LinuxFileKind.BINARY

@StringRes
internal fun linuxFileKindLabel(kind: LinuxFileKind): Int = when (kind) {
    LinuxFileKind.SHELL -> R.string.linux_file_kind_shell
    LinuxFileKind.PYTHON -> R.string.linux_file_kind_python
    LinuxFileKind.JAVASCRIPT -> R.string.linux_file_kind_javascript
    LinuxFileKind.TYPESCRIPT -> R.string.linux_file_kind_typescript
    LinuxFileKind.C -> R.string.linux_file_kind_c
    LinuxFileKind.CPP -> R.string.linux_file_kind_cpp
    LinuxFileKind.GO -> R.string.linux_file_kind_go
    LinuxFileKind.RUST -> R.string.linux_file_kind_rust
    LinuxFileKind.JAVA -> R.string.linux_file_kind_java
    LinuxFileKind.KOTLIN -> R.string.linux_file_kind_kotlin
    LinuxFileKind.RUBY -> R.string.linux_file_kind_ruby
    LinuxFileKind.PERL -> R.string.linux_file_kind_perl
    LinuxFileKind.PHP -> R.string.linux_file_kind_php
    LinuxFileKind.JSON -> R.string.linux_file_kind_json
    LinuxFileKind.YAML -> R.string.linux_file_kind_yaml
    LinuxFileKind.MARKDOWN -> R.string.linux_file_kind_markdown
    LinuxFileKind.CONFIG -> R.string.linux_file_kind_config
    LinuxFileKind.TEXT -> R.string.linux_file_kind_text
    LinuxFileKind.ARCHIVE -> R.string.linux_file_kind_archive
    LinuxFileKind.IMAGE -> R.string.linux_file_kind_image
    LinuxFileKind.BINARY -> R.string.linux_file_kind_binary
}

internal fun linuxLineEndingLabel(ending: LinuxLineEnding): String = when (ending) {
    LinuxLineEnding.LF -> "LF"
    LinuxLineEnding.CRLF -> "CRLF"
    LinuxLineEnding.CR -> "CR"
    LinuxLineEnding.MIXED -> "LF/CRLF"
}

// ── 错误 ──

@StringRes
internal fun linuxFileErrorText(code: LinuxFileErrorCode): Int = when (code) {
    LinuxFileErrorCode.NAME_INVALID -> R.string.linux_files_error_name_invalid
    LinuxFileErrorCode.NAME_EXISTS -> R.string.linux_files_error_name_exists
    LinuxFileErrorCode.NOT_FOUND -> R.string.linux_files_error_not_found
    LinuxFileErrorCode.OUTSIDE_ROOTFS -> R.string.linux_files_error_outside_rootfs
    LinuxFileErrorCode.TARGET_INSIDE_SOURCE -> R.string.linux_files_error_inside_source
    LinuxFileErrorCode.CHANGED -> R.string.linux_files_error_changed
    LinuxFileErrorCode.NOT_READY -> R.string.linux_files_error_not_ready
    LinuxFileErrorCode.IO -> R.string.linux_files_error_io
}

/** 数据层的异常收敛为错误码；协程取消必须由调用方先行重抛。 */
internal fun Throwable.toLinuxFileErrorCode(): LinuxFileErrorCode = when (this) {
    is com.promenar.infinity.domain.linux.LinuxFileException -> code
    is com.promenar.infinity.domain.linux.LinuxUnavailableException -> LinuxFileErrorCode.NOT_READY
    else -> LinuxFileErrorCode.IO
}

// ── 十六进制 ──

internal data class LinuxHexRow(val offset: String, val hex: String, val ascii: String)

/** 每行 [bytesPerRow] 字节；不足一行时十六进制列补空格，保持 ASCII 列对齐。 */
internal fun linuxHexRows(bytes: ByteArray, startOffset: Long, bytesPerRow: Int): List<LinuxHexRow> =
    (bytes.indices step bytesPerRow).map { index ->
        val chunk = bytes.copyOfRange(index, minOf(index + bytesPerRow, bytes.size))
        val hex = chunk.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        LinuxHexRow(
            offset = "%08x".format(startOffset + index),
            hex = hex.padEnd(bytesPerRow * 3 - 1),
            ascii = chunk.joinToString("") { byte ->
                val value = byte.toInt() and 0xFF
                if (value in 0x20..0x7E) value.toChar().toString() else "."
            },
        )
    }

/** 每行最多能放下多少字节：偏移 8 字符 + 2 空格 + 每字节 3（十六进制）+ 1（ASCII）+ 2 空格。 */
internal fun linuxHexBytesPerRow(availableChars: Int): Int =
    listOf(16, 8, 4).firstOrNull { 8 + 2 + it * 4 + 1 <= availableChars } ?: 4

/** 默认压缩包名：单项用其名称，多项用所在目录名，根目录用 `archive`。 */
internal fun defaultLinuxArchiveName(paths: List<String>, currentDirectory: String): String {
    val base = if (paths.size == 1) paths.single().substringAfterLast('/')
    else currentDirectory.substringAfterLast('/').ifEmpty { "archive" }
    return "${base.ifEmpty { "archive" }}.tar.gz"
}

internal fun ensureTarGzSuffix(name: String): String {
    val trimmed = name.trim()
    return if (trimmed.endsWith(".tar.gz") || trimmed.endsWith(".tgz")) trimmed else "$trimmed.tar.gz"
}
