package com.promenar.nexara.ui.hub

import com.promenar.nexara.BuildConfig

/** 设置页底部展示与复制的构建信息；全部取自 [BuildConfig]，不在界面写死。 */
internal data class AppVersionInfo(
    val versionName: String,
    val versionCode: Int,
    /** 与 `BuildConfig.DISTRIBUTION` 同值：`sideload` 或 `play`。 */
    val channel: String,
    /** 构建时写入的提交短 SHA；取不到时为 `unknown` 或空串。 */
    val commit: String,
) {
    /** 提交短 SHA；空白按“取不到”处理，与构建脚本的回退值一致。 */
    val commitOrUnknown: String get() = commit.trim().ifEmpty { UNKNOWN_COMMIT }

    val isSideload: Boolean get() = channel == CHANNEL_SIDELOAD

    companion object {
        const val UNKNOWN_COMMIT = "unknown"
        const val CHANNEL_SIDELOAD = "sideload"

        fun current() = AppVersionInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            channel = BuildConfig.DISTRIBUTION,
            commit = BuildConfig.GIT_COMMIT_SHORT,
        )
    }
}

/**
 * 底部一行：“Nexara 版本名（versionCode）· 渠道”。模板与渠道名来自资源，
 * 模板按 `%1$s` 版本名、`%2$d` versionCode、`%3$s` 渠道名取参。
 */
internal fun appVersionFooterText(info: AppVersionInfo, template: String, channelLabel: String): String =
    String.format(template, info.versionName, info.versionCode, channelLabel)

/** 复制到剪贴板的版本信息：版本名、versionCode、渠道、提交短 SHA；模板按此顺序取 `%1$s %2$d %3$s %4$s`。 */
internal fun appVersionCopyText(info: AppVersionInfo, template: String, channelLabel: String): String =
    String.format(template, info.versionName, info.versionCode, channelLabel, info.commitOrUnknown)
