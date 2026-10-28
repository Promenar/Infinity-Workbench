package com.promenar.infinity.data.rag

/** 查询改写依次使用单独设置、快速模型与主模型；空白标识视为未设置。 */
fun resolveQueryRewriteModel(
    overrideModelId: String?,
    quickModelId: String?,
    mainModelId: String?,
): String? = sequenceOf(overrideModelId, quickModelId, mainModelId)
    .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
    .firstOrNull()
