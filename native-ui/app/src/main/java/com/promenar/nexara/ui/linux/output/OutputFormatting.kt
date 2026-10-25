package com.promenar.nexara.ui.linux.output

import java.util.Locale

/** 标签行使用的紧凑字节数：B、KB、MB，一位小数，1024 进制。 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0).replace(".0 ", " ")
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024)).replace(".0 ", " ")
}

/** 秒数保留一位小数，供“12.4 秒”文案使用。 */
fun formatSeconds(durationMs: Long): String = String.format(Locale.ROOT, "%.1f", durationMs / 1_000.0)
