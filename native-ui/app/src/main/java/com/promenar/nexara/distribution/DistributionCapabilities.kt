package com.promenar.nexara.distribution

import com.promenar.nexara.BuildConfig

/** 发行能力只描述制品是否支持该功能；实际授权状态由后续业务另行检查。 */
object DistributionCapabilities {
    val channel: String get() = BuildConfig.DISTRIBUTION
    val allFilesAccessAvailable: Boolean get() = BuildConfig.ALL_FILES_ACCESS_AVAILABLE

    /** 精确闹钟调度定时任务（侧载版）；Play 版只有非精确调度，最短 15 分钟。 */
    val exactAlarmAvailable: Boolean get() = BuildConfig.EXACT_ALARM_AVAILABLE

    /** 后台任务与触发轮次可由命令前台服务保活（侧载版）；Play 版只在应用可见时保证运行。 */
    val backgroundKeepAliveAvailable: Boolean get() = BuildConfig.DISTRIBUTION == "sideload"

    internal fun diagnosticFields(): Map<String, Any> = mapOf(
        "distribution" to channel,
        "allFilesAccessAvailable" to allFilesAccessAvailable,
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
    )
}
