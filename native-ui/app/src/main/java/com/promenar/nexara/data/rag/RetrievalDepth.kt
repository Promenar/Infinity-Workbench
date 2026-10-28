package com.promenar.nexara.data.rag

/** 档位由四个原始参数推导，不单独持久化。 */
enum class RetrievalDepth(val memoryLimit: Int, val docLimit: Int, val rerankTopK: Int, val rerankFinalK: Int) {
    LEAN(3, 4, 20, 3),
    BALANCED(5, 8, 30, 5),
    COMPREHENSIVE(8, 15, 60, 10),
}

fun RagConfiguration.withRetrievalDepth(depth: RetrievalDepth): RagConfiguration = copy(
    memoryLimit = depth.memoryLimit,
    docLimit = depth.docLimit,
    rerankTopK = depth.rerankTopK,
    rerankFinalK = depth.rerankFinalK,
)

/** null 表示自定义参数，只有四项完全匹配时才命中档位。 */
fun RagConfiguration.retrievalDepth(): RetrievalDepth? = RetrievalDepth.entries.firstOrNull {
    memoryLimit == it.memoryLimit && docLimit == it.docLimit &&
        rerankTopK == it.rerankTopK && rerankFinalK == it.rerankFinalK
}
