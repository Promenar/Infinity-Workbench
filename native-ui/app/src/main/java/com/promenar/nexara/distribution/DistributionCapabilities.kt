package com.promenar.nexara.distribution

import com.promenar.nexara.BuildConfig

/** 发行能力只描述制品是否支持该功能；实际授权状态由后续业务另行检查。 */
object DistributionCapabilities {
    val channel: String get() = BuildConfig.DISTRIBUTION
    val allFilesAccessAvailable: Boolean get() = BuildConfig.ALL_FILES_ACCESS_AVAILABLE

    internal fun diagnosticFields(): Map<String, Any> = mapOf(
        "distribution" to channel,
        "allFilesAccessAvailable" to allFilesAccessAvailable,
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
    )
}
