package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.KgEdge
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.chat.manager.KgContextResult
import com.promenar.nexara.ui.chat.manager.KgProvider

class MicroGraphKgAdapter(
    private val microGraphExtractor: MicroGraphExtractor
) : KgProvider {
    override suspend fun extractContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>
    ): KgContextResult? {
        val searchResults = topKResults.map { ref ->
            SearchResult(
                id = ref.id,
                content = ref.content,
                similarity = ref.score,
                createdAt = UNKNOWN_CREATED_AT_SENTINEL
            )
        }
        val result = microGraphExtractor.extract(
            topKResults = searchResults,
            query = query,
            sessionId = sessionId
        ) ?: return null
        return KgContextResult(
            context = result.context,
            paths = result.toKgPaths()
        )
    }

    /**
     * 将 [MicroGraphResult] 的 nodes/edges 确定性映射为可序列化的 [KgPath] 列表。
     *
     * 映射规则（不编造任何节点/关系）：
     * 1. 节点：以 name 作为 KgNode.id/label，重复 name 做**规范化选择**（按 type 升序、再按 metadata
     *    升序、null 视为最小排在最前，取最小候选），与输入顺序无关；输入重排得到完全相同结果。
     * 2. 边：仅保留 source/target 均存在于节点表中的边（悬挂边被丢弃，不补造节点）；自环保留。
     * 3. 去重：仅去重完全相同（含 weight）的边；权重不同的并行边全部保留。KgEdge 是含 weight 的 data class，
     *    LinkedHashSet 天然按全字段去重，保留首次出现。
     * 4. 规范化排序：nodes/edges 按明确 comparator 排序，输入重排得到完全相同结果。
     * 5. 弱连通分量：按无向连通性把存活边拆成多个稳定 KgPath（按各分量最小节点 id 排序）。
     * 6. 空图/无边存活 → emptyList()（无关系即无路径）；孤立节点（不参与任何存活边）丢弃。
     * 7. queryKeywords 取真实非空 result.query，绝不臆造；reasoning 始终为 null。
     */
    private fun MicroGraphResult.toKgPaths(): List<KgPath> {
        if (edges.isEmpty()) return emptyList()

        val nodeByName = LinkedHashMap<String, KgNode>()
        for (n in nodes) {
            val candidate = KgNode(id = n.name, label = n.name, type = n.type, metadata = n.metadata)
            val incumbent = nodeByName[n.name]
            if (incumbent == null || NODE_CANDIDATE_COMPARATOR.compare(candidate, incumbent) < 0) {
                nodeByName[n.name] = candidate
            }
        }

        val dedupedEdges = LinkedHashSet<KgEdge>()
        for (e in edges) {
            if (nodeByName.containsKey(e.source) && nodeByName.containsKey(e.target)) {
                dedupedEdges.add(
                    KgEdge(
                        sourceId = e.source,
                        targetId = e.target,
                        relation = e.relation,
                        weight = e.weight
                    )
                )
            }
        }
        if (dedupedEdges.isEmpty()) return emptyList()

        val queryKeywords = if (query.isNotBlank()) listOf(query) else emptyList()

        return weaklyConnectedComponents(dedupedEdges).map { nodeIds ->
            KgPath(
                queryKeywords = queryKeywords,
                nodes = nodeIds.mapNotNull { nodeByName[it] }.sortedWith(NODE_COMPARATOR),
                edges = dedupedEdges.filter { it.sourceId in nodeIds }.sortedWith(EDGE_COMPARATOR),
                reasoning = null
            )
        }
    }

    /** 在无向意义上计算 [edges] 覆盖节点的弱连通分量，按各分量最小节点 id 稳定排序返回。 */
    private fun weaklyConnectedComponents(edges: Set<KgEdge>): List<Set<String>> {
        val parent = HashMap<String, String>()

        fun find(x: String): String {
            var root = x
            while (parent[root] != null && parent[root] != root) {
                root = parent[root]!!
            }
            var cur = x
            while (parent[cur] != null && parent[cur] != root) {
                val next = parent[cur]!!
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (e in edges) {
            parent.putIfAbsent(e.sourceId, e.sourceId)
            parent.putIfAbsent(e.targetId, e.targetId)
            val ra = find(e.sourceId)
            val rb = find(e.targetId)
            if (ra != rb) parent[ra] = rb
        }

        val groups = LinkedHashMap<String, MutableSet<String>>()
        for (n in parent.keys) {
            val root = find(n)
            groups.getOrPut(root) { linkedSetOf() }.add(n)
        }
        return groups.values.sortedBy { it.min() }.map { it.toSet() }
    }

    private companion object {
        /** RagReference 无时间字段，未知时间使用 0L 哨兵，绝不使用墙上时钟。 */
        const val UNKNOWN_CREATED_AT_SENTINEL = 0L

        /**
         * 重复 name 候选节点的规范化选择比较器：先按 type 升序，再按 metadata 升序
         * （null 视为最小，排在任何非 null 值之前）。取最小候选为该 name 的唯一代表，
         * 使结果与输入顺序无关。compareBy 对 null selector 返回值按“小于非 null”处理，规则明确且稳定。
         */
        val NODE_CANDIDATE_COMPARATOR: Comparator<KgNode> =
            compareBy({ it.type }, { it.metadata })

        val NODE_COMPARATOR: Comparator<KgNode> =
            compareBy({ it.id }, { it.label }, { it.type }, { it.metadata })
        val EDGE_COMPARATOR: Comparator<KgEdge> =
            compareBy({ it.sourceId }, { it.targetId }, { it.relation }, { it.weight })
    }
}
