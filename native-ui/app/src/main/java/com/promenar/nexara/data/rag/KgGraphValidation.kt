package com.promenar.nexara.data.rag

import kotlinx.serialization.json.*
import com.promenar.nexara.data.local.db.KgIdentity

/** 入库、提示词与展示路径共享的已校验图，端点统一使用规范节点的显示名。 */
class ValidatedGraph private constructor(val nodes: List<ExtractedNode>, val edges: List<ExtractedEdge>) {
    val context: String get() = edges.joinToString("\n") { "- ${it.source} --[${it.relation}]--> ${it.target}" }

    fun toExtractionResult() = ExtractionResult(nodes, edges)

    companion object {
        fun from(nodes: List<ExtractedNode>, edges: List<ExtractedEdge>): ValidatedGraph {
            val canonical = nodes.asSequence().take(KgGraphValidation.MAX_RAW_NODES)
                .map { it.copy(name = clean(it.name), type = clean(it.type).ifBlank { "concept" }, metadata = it.metadata?.take(4096)) }
                .filter { it.name.isNotBlank() && it.name.length <= 512 && it.type.length <= 512 }
                .sortedWith(compareBy({ identity(it.name) }, { it.type }, { it.metadata }, { it.name }))
                .distinctBy { identity(it.name) }
                .take(KgGraphValidation.MAX_NODES)
                .associateBy { identity(it.name) }
            val validEdges = edges.asSequence().take(KgGraphValidation.MAX_RAW_EDGES).mapNotNull { edge ->
                val source = canonical[identity(edge.source)] ?: return@mapNotNull null
                val target = canonical[identity(edge.target)] ?: return@mapNotNull null
                val relation = clean(edge.relation)
                if (relation.isBlank() || relation.length > 512 || !edge.weight.isFinite() || edge.weight !in 0.0..1.0) return@mapNotNull null
                edge.copy(source = source.name, target = target.name, relation = relation)
            }.distinct().sortedWith(compareBy({ it.source }, { it.target }, { it.relation }, { it.weight }))
                .take(KgGraphValidation.MAX_EDGES).toList()
            val connected = validEdges.flatMap { listOf(it.source, it.target) }.toSet()
            return ValidatedGraph(canonical.values.filter { it.name in connected }, validEdges)
        }

        fun identity(name: String): String = KgIdentity.normalize(name)
        private fun clean(value: String) = KgIdentity.display(value)
    }
}

object KgGraphValidation {
    const val MAX_NODES = 256
    const val MAX_EDGES = 512
    const val MAX_RAW_NODES = 1024
    const val MAX_RAW_EDGES = 4096
    private const val MAX_RESPONSE_CHARS = 1024 * 1024

    /** 分块先解析；端点在全量分块合并后校验，允许实体与引用位于相邻块。 */
    fun parse(content: String): ExtractionResult {
        if (content.length > MAX_RESPONSE_CHARS) return parseFailure()
        val first = content.indexOf('{')
        val last = content.lastIndexOf('}')
        if (first < 0 || last <= first) return parseFailure()
        return try {
            val root = Json.parseToJsonElement(content.substring(first, last + 1)).jsonObject
            val nodes = root["nodes"] as? JsonArray ?: return parseFailure()
            val edges = root["edges"] as? JsonArray ?: return parseFailure()
            ExtractionResult(
                nodes = nodes.take(MAX_RAW_NODES).mapNotNull { value ->
                    val node = value as? JsonObject ?: return@mapNotNull null
                    ExtractedNode(node.string("name"), node.string("type").ifBlank { "concept" }, node["metadata"]?.toString())
                },
                edges = edges.take(MAX_RAW_EDGES).mapNotNull { value ->
                    val edge = value as? JsonObject ?: return@mapNotNull null
                    ExtractedEdge(
                        edge.string("source"), edge.string("target"), edge.string("relation"),
                        if ("weight" in edge) edge.string("weight").toDoubleOrNull() ?: Double.NaN else 1.0,
                    )
                },
            )
        } catch (_: Exception) {
            parseFailure()
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun parseFailure() = ExtractionResult.failure(KgExtractionStatus.PARSE_FAILURE, "知识图谱响应不是有效的 nodes/edges JSON")
}
