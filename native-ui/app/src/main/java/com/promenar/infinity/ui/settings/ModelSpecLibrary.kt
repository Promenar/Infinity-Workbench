package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.model.catalog.CatalogUpdatePhase
import com.promenar.infinity.data.model.catalog.CatalogUpdateStatus
import java.text.NumberFormat

/*
 * 规格库：界面上对模型目录（上下文长度、能力等规格数据）的称呼，与“同步”（从服务商拉模型列表）分开。
 * 本文件集中规格库状态到界面文案的映射：纯函数部分可在 JVM 上测试，文案统一由 [specLibraryText] 系列取用。
 */

/** 规格库的一个版本：[date] 为 MM-dd；内置快照时为 null。 */
internal data class SpecLibraryVersion(val date: String?, val records: Int) {
    val bundled: Boolean get() = date == null

    companion object {
        val Bundled = SpecLibraryVersion(null, 0)
    }
}

internal enum class SpecLibraryFailure {
    /** 下载或签名校验未通过，现有规格库保持不变。 */
    UPDATE,
    /** 新版本已下载并缓存，但写入模型规格失败。 */
    APPLY,
}

/**
 * 模型管理页需要的规格库状态。
 * - [current]：⋮ 菜单“更新规格库”第二行显示的版本；null 表示新版本未能应用、正在使用的版本无法确定；
 * - [kept]：失败时沿用的版本，规则同 [current]。
 */
internal data class SpecLibraryUi(
    val current: SpecLibraryVersion?,
    val updating: Boolean,
    val failure: SpecLibraryFailure?,
    val kept: SpecLibraryVersion?,
)

/** 一次手动更新完成：[changed] 为 false 表示服务端没有新版本。 */
internal data class SpecLibraryCompletion(val version: SpecLibraryVersion, val changed: Boolean)

private val IsoDate = Regex("""^\d{4}-(\d{2})-(\d{2})""")

/** 目录生成时间（ISO 8601）取 MM-dd；格式不符时返回 null。 */
internal fun specLibraryDate(generatedAt: String?): String? =
    generatedAt?.let { IsoDate.find(it) }?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

/** 状态里记录的版本：未缓存过下载版本即为内置快照。 */
internal fun CatalogUpdateStatus.specVersion(): SpecLibraryVersion {
    if (!cached) return SpecLibraryVersion.Bundled
    val date = specLibraryDate(generatedAt) ?: return SpecLibraryVersion.Bundled
    return SpecLibraryVersion(date, recordCount)
}

private val CatalogUpdateStatus.settled: Boolean
    get() = phase == CatalogUpdatePhase.IDLE || phase == CatalogUpdatePhase.CURRENT

/** 记住最近一次已生效的状态（空闲或刚更新完）；更新中与失败不覆盖。 */
internal fun nextSettledStatus(previous: CatalogUpdateStatus?, status: CatalogUpdateStatus?): CatalogUpdateStatus? =
    if (status != null && status.settled) status else previous

/**
 * 状态映射。更新中与下载失败时状态仍带着原版本；应用失败时状态带的是未生效的新版本，
 * 正在使用的版本只能取页面见过的最近一次生效状态 [settled]，没见过时为 null。
 */
internal fun specLibraryUi(status: CatalogUpdateStatus, settled: CatalogUpdateStatus?): SpecLibraryUi = when (status.phase) {
    CatalogUpdatePhase.IDLE, CatalogUpdatePhase.CURRENT ->
        SpecLibraryUi(status.specVersion(), updating = false, failure = null, kept = null)
    CatalogUpdatePhase.UPDATING ->
        SpecLibraryUi(status.specVersion(), updating = true, failure = null, kept = null)
    CatalogUpdatePhase.FAILED -> {
        val version = status.specVersion()
        SpecLibraryUi(version, updating = false, failure = SpecLibraryFailure.UPDATE, kept = version)
    }
    CatalogUpdatePhase.APPLY_FAILED -> {
        val version = settled?.specVersion()
        SpecLibraryUi(version, updating = false, failure = SpecLibraryFailure.APPLY, kept = version)
    }
}

/** 更新中 → 已是最新：返回新版本，用于底部提示；其余转换返回 null。 */
internal fun specLibraryCompletion(before: CatalogUpdateStatus?, after: CatalogUpdateStatus?): SpecLibraryCompletion? {
    if (before?.phase != CatalogUpdatePhase.UPDATING || after?.phase != CatalogUpdatePhase.CURRENT) return null
    val changed = !before.cached || before.version != after.version || before.generatedAt != after.generatedAt
    return SpecLibraryCompletion(after.specVersion(), changed)
}

/** 页面内的规格库状态：跟踪最近生效版本，并在一次更新完成时回调 [onComplete]。 */
@Composable
internal fun rememberSpecLibraryUi(
    status: CatalogUpdateStatus?,
    onComplete: (SpecLibraryCompletion) -> Unit,
): SpecLibraryUi? {
    var settled by remember { mutableStateOf(nextSettledStatus(null, status)) }
    var previous by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        settled = nextSettledStatus(settled, status)
        specLibraryCompletion(previous, status)?.let(onComplete)
        previous = status
    }
    return remember(status, settled) { status?.let { specLibraryUi(it, settled) } }
}

// ---- 文案（中英文资源在 ux3_strings.xml 的“模型管理：规格库”一节） ----

/** ⋮ 菜单项第二行：“当前 MM-dd 版 · N 条”/“内置快照”/“新版本未应用”。 */
@Composable
internal fun specLibraryVersionText(version: SpecLibraryVersion?): String = when {
    version == null -> stringResource(R.string.model_spec_version_unapplied)
    version.bundled -> stringResource(R.string.model_spec_bundled)
    else -> pluralStringResource(
        R.plurals.model_spec_version,
        version.records,
        version.date.orEmpty(),
        NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(version.records),
    )
}

/** 失败原因。 */
@Composable
internal fun specLibraryFailureText(failure: SpecLibraryFailure): String = when (failure) {
    SpecLibraryFailure.UPDATE -> stringResource(R.string.model_spec_failed_update)
    SpecLibraryFailure.APPLY -> stringResource(R.string.model_spec_failed_apply)
}

/** 失败时沿用的版本：“沿用 MM-dd 版”/“沿用内置快照”/“沿用现有版本”。 */
@Composable
internal fun specLibraryKeptText(kept: SpecLibraryVersion?): String = when {
    kept == null -> stringResource(R.string.model_spec_kept_unknown)
    kept.bundled -> stringResource(R.string.model_spec_kept_bundled)
    else -> stringResource(R.string.model_spec_kept, kept.date.orEmpty())
}

/** 更新完成的底部提示。 */
@Composable
internal fun specLibraryCompletionText(completion: SpecLibraryCompletion): String {
    val version = completion.version
    val label = if (version.bundled) stringResource(R.string.model_spec_bundled) else version.date.orEmpty()
    return stringResource(if (completion.changed) R.string.model_spec_updated else R.string.model_spec_up_to_date, label)
}
