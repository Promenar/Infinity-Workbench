package com.promenar.nexara.debug.linuxprobe

/** 各阶段即使异常或取消，也必须到达宿主兜底与终态发布。 */
internal fun finalizeProbe(guestCleanup: () -> Unit, hostCleanup: () -> Unit, publishFinal: () -> Unit) {
    try { guestCleanup() } finally {
        try { hostCleanup() } finally { publishFinal() }
    }
}
