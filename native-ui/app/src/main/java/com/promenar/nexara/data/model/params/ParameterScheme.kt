package com.promenar.nexara.data.model.params

/** 用户为某个模型保存的一组采样参数。 */
data class ParameterScheme(
    val id: String,
    /** 稳定模型 id（provider::remote）。 */
    val modelKey: String,
    val name: String,
    val values: SamplingValues,
    val createdAt: Long,
    val updatedAt: Long,
)
