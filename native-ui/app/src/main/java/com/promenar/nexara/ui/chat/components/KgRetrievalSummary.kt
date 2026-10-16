package com.promenar.nexara.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.KgIdentity
import com.promenar.nexara.data.model.KgPath

/** 实体按业务身份统计，避免不同路径中的局部节点 ID 重复或碰撞。 */
internal fun kgEntityCount(paths: List<KgPath>): Int = paths.flatMap { path ->
    val used = path.edges.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
    path.nodes.filter { it.id in used }
}.map { KgIdentity.normalize(it.label) to KgIdentity.normalize(it.type) }.distinct().size

@Composable
internal fun kgSummarySegments(paths: List<KgPath>): List<String> = buildList {
    if (paths.isEmpty()) return@buildList
    val nonEmpty = paths.filter { it.edges.isNotEmpty() }
    add(stringResource(R.string.kg_hit_summary, kgEntityCount(nonEmpty), nonEmpty.size))
    listOf("persistent" to R.string.kg_source_persistent, "jit" to R.string.kg_source_jit).forEach { (origin, label) ->
        val selected = nonEmpty.filter { origin in it.origins }
        if (selected.isNotEmpty()) add(stringResource(label) + ": " + stringResource(R.string.kg_hit_summary, kgEntityCount(selected), selected.size))
    }
    val omitted = paths.maxOfOrNull { it.omittedPathCount } ?: 0
    if (omitted > 0) add(stringResource(R.string.kg_trimmed_summary, omitted))
}
