package com.promenar.nexara.data.local.inference

import com.promenar.nexara.BuildConfig

/**
 * 将构建变体能力与已恢复的用户偏好分离，防止不含原生库的 Release 进入本地加载链。
 */
class LocalInferenceRuntimeGate(
    val isAvailable: Boolean,
) {
    fun startupModelPath(
        localModelsEnabled: Boolean,
        autoLoadEnabled: Boolean,
        persistedModelPath: String?,
    ): String? = persistedModelPath?.takeIf {
        isAvailable && localModelsEnabled && autoLoadEnabled && it.isNotBlank()
    }

    fun requireAvailable() {
        check(isAvailable) { "当前发行版本不包含本地推理能力" }
    }

    companion object {
        fun fromBuildConfig(): LocalInferenceRuntimeGate =
            LocalInferenceRuntimeGate(BuildConfig.LOCAL_INFERENCE_AVAILABLE)
    }
}
