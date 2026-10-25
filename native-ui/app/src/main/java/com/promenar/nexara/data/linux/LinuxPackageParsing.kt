package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.*
import java.util.Locale

internal object LinuxPackageParsing {
    private val namePattern = Regex("[a-zA-Z0-9_.+][a-zA-Z0-9_.+\\-]*")
    private val packageVersion = Regex("^(.+)-([0-9][^\\s]*)$")

    fun installed(text: String, world: Set<String>): List<LinuxPackage> {
        val result = mutableListOf<LinuxPackage>()
        val fields = mutableMapOf<Char, String>()
        fun finish() {
            val name = fields['P']
            val version = fields['V']
            if (name != null && validName(name) && !version.isNullOrBlank()) {
                result += LinuxPackage(name, version, fields['T']?.takeIf(String::isNotBlank),
                    fields['I']?.toLongOrNull()?.takeIf { it >= 0 }, true, explicit = name in world)
            }
            fields.clear()
        }
        text.lineSequence().forEach { line ->
            if (line.isBlank()) finish()
            else if (line.length >= 2 && line[1] == ':' && line[0] in "PVTI") fields[line[0]] = line.substring(2)
        }
        finish()
        return result
    }

    fun world(text: String): Set<String> = text.lineSequence().map { it.substringBefore('#').trim() }
        .filter(String::isNotEmpty).map { it.takeWhile { char -> char !in "@<>=~!" } }
        .filter(String::isNotEmpty).toSet()

    fun search(text: String): List<LinuxPackage> = text.lineSequence().mapNotNull { line ->
        val token = line.substringBefore(' ')
        val match = packageVersion.matchEntire(token) ?: return@mapNotNull null
        val (name, version) = match.destructured
        if (!validName(name)) return@mapNotNull null
        LinuxPackage(name, version, line.substringAfter(" - ", "").takeIf(String::isNotBlank), null, false)
    }.toList()

    fun upgrades(text: String): Map<String, String> = text.lineSequence().filter { "[upgradable from:" in it }
        .mapNotNull { line -> packageVersion.matchEntire(line.substringBefore(' '))?.let {
            it.groupValues[1] to it.groupValues[2]
        } }.toMap()

    fun validName(name: String): Boolean = namePattern.matches(name) && name.any { it.isLetterOrDigit() }

    fun failure(output: String): LinuxFailure {
        val lower = output.lowercase(Locale.ROOT)
        val code = when {
            "no space left" in lower || "disk full" in lower -> LinuxFailureCode.INSUFFICIENT_STORAGE
            listOf("network", "dns:", "temporary error", "timed out", "timeout", "tls:", "ssl", "connection refused", "connection reset", "http ", "remote server returned error").any { it in lower } -> LinuxFailureCode.NETWORK
            else -> LinuxFailureCode.PACKAGE_INSTALL_FAILED
        }
        return LinuxFailure(code, output)
    }
}

internal class LinuxPackageProgress {
    var step: Int? = null
        private set
    var totalSteps: Int? = null
        private set
    // apk 的 progress-fd 含额外权重；没有明确的下载字节信息时保持未知。
    val downloadedBytes: Long? = null
    var totalBytes: Long? = null
        private set
    var outputTail: String = ""
        private set

    @Synchronized fun append(text: String) {
        val combined = outputTail + text
        steps.findAll(combined).lastOrNull()?.let { match ->
            val current = match.groupValues[1].toIntOrNull()
            val total = match.groupValues[2].toIntOrNull()
            if (current != null && total != null && current in 1..total) { step = current; totalSteps = total }
        }
        download.findAll(combined).lastOrNull()?.let { match ->
            val factor = when (match.groupValues[2]) { "KiB" -> 1024L; "MiB" -> 1048576L; "GiB" -> 1073741824L; else -> 1L }
            match.groupValues[1].toDoubleOrNull()?.times(factor)?.takeIf { it.isFinite() && it in 0.0..Long.MAX_VALUE.toDouble() }
                ?.let { totalBytes = it.toLong() }
        }
        outputTail = tail(combined)
    }

    companion object {
        private val steps = Regex("\\(\\s*(\\d+)/(\\d+)\\)")
        private val download = Regex("Need to download ([0-9]+(?:\\.[0-9]+)?) (B|KiB|MiB|GiB) of packages\\.")
        fun tail(text: String): String {
            // 从尾部按 Unicode 码点计数，保证 UTF-8 不超过 4 KiB 且不截断代理对。
            var start = text.length
            var bytes = 0
            while (start > 0) {
                val point = text.codePointBefore(start)
                val size = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
                if (bytes + size > 4096) break
                bytes += size
                start -= Character.charCount(point)
            }
            return text.substring(start)
        }
    }
}
