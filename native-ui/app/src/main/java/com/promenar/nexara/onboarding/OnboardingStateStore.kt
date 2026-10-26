package com.promenar.nexara.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OnboardingStep {
    LANGUAGE,
    LINUX,
    PROVIDER,
    MODEL,
    AGENT,
    FIRST_CHAT,
    COMPLETED,
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val languageCode: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val agentId: String? = null,
    val sessionId: String? = null,
)

/** 只有侧载渠道的 Linux 运行时可用且主 ABI 为 arm64 时，引导才提供 Linux 安装步骤。 */
fun onboardingOffersLinux(linuxRuntimeAvailable: Boolean, primaryAbi: String?): Boolean =
    linuxRuntimeAvailable && primaryAbi == LINUX_SUPPORTED_ABI

private const val LINUX_SUPPORTED_ABI = "arm64-v8a"

/**
 * 首次引导的持久检查点。只有当前步骤的成功事件可以推进，失败事件保持原步骤，
 * 因而 Activity 旋转或进程重建后不会跳过尚未完成的业务动作。
 *
 * [linuxOffered] 决定语言之后是否经过 Linux 步骤；当前版本不提供 Linux 时，
 * 停在 Linux 检查点的旧状态恢复为连接提供商。
 */
class OnboardingStateStore private constructor(
    private val preferences: SharedPreferences,
    private val legacyPreferences: SharedPreferences?,
    val linuxOffered: Boolean,
) {
    constructor(context: Context, linuxOffered: Boolean = false) : this(
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE),
        linuxOffered = linuxOffered,
    )

    private val lock = Any()
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun selectLanguage(languageCode: String): Boolean {
        if (languageCode !in SUPPORTED_LANGUAGES) return false
        return transition(OnboardingStep.LANGUAGE) { current ->
            current.copy(
                step = if (linuxOffered) OnboardingStep.LINUX else OnboardingStep.PROVIDER,
                languageCode = languageCode,
            )
        }
    }

    /** 开始后台安装或选择跳过都完成 Linux 步骤；安装进度由 Linux 运行时自身维护。 */
    fun completeLinuxStep(): Boolean = transition(OnboardingStep.LINUX) { current ->
        current.copy(step = OnboardingStep.PROVIDER)
    }

    /** 提供商已经过一次探测并保存成功，直接进入选模型，不再要求单独的验证连接步骤。 */
    fun recordProviderConnected(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        return transition(OnboardingStep.PROVIDER) { current ->
            current.copy(step = OnboardingStep.MODEL, providerId = providerId)
        }
    }

    fun selectModel(modelId: String?): Boolean {
        val normalized = modelId?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return transition(OnboardingStep.MODEL) { current ->
            current.copy(step = OnboardingStep.AGENT, modelId = normalized)
        }
    }

    /** 返回连接提供商时保留已保存的提供商 ID，重新连接会更新同一个提供商。 */
    fun returnToProvider(): Boolean = transition(OnboardingStep.MODEL) { current ->
        current.copy(step = OnboardingStep.PROVIDER, modelId = null)
    }

    fun returnToModel(): Boolean = transition(OnboardingStep.AGENT) { current ->
        current.copy(step = OnboardingStep.MODEL, modelId = null)
    }

    fun recordAgentCreated(agentId: String, sessionId: String): Boolean {
        if (agentId.isBlank() || sessionId.isBlank()) return false
        return transition(OnboardingStep.AGENT) { current ->
            if (current.modelId.isNullOrBlank()) return@transition null
            current.copy(
                step = OnboardingStep.FIRST_CHAT,
                agentId = agentId,
                sessionId = sessionId,
            )
        }
    }

    fun recordFirstChatResult(sessionId: String, assistantSucceeded: Boolean): Boolean {
        if (!assistantSucceeded) return false
        val advanced = transition(OnboardingStep.FIRST_CHAT) { current ->
            if (current.sessionId != sessionId) null else current.copy(step = OnboardingStep.COMPLETED)
        }
        if (advanced) {
            legacyPreferences?.edit()?.putBoolean(LEGACY_COMPLETED_KEY, true)?.commit()
        }
        return advanced
    }

    private fun transition(
        expected: OnboardingStep,
        transform: (OnboardingState) -> OnboardingState?,
    ): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.step != expected) return@synchronized false
        val updated = transform(current) ?: return@synchronized false
        if (!persist(updated)) return@synchronized false
        _state.value = updated
        true
    }

    private fun loadInitialState(): OnboardingState {
        val persistedStep = preferences.getString(KEY_STEP, null)
        if (persistedStep == null && legacyPreferences?.getBoolean(LEGACY_COMPLETED_KEY, false) == true) {
            return OnboardingState(step = OnboardingStep.COMPLETED).also(::persist)
        }
        return OnboardingState(
            step = restoreStep(persistedStep),
            languageCode = preferences.getString(KEY_LANGUAGE, null),
            providerId = preferences.getString(KEY_PROVIDER, null),
            modelId = preferences.getString(KEY_MODEL, null),
            agentId = preferences.getString(KEY_AGENT, null),
            sessionId = preferences.getString(KEY_SESSION, null),
        )
    }

    private fun restoreStep(serialized: String?): OnboardingStep {
        val step = when (serialized) {
            null -> OnboardingStep.LANGUAGE
            // 旧版本的“验证连接”检查点合并进连接提供商，保留的 provider_id 用于更新同一个提供商。
            LEGACY_CONNECTION_STEP -> OnboardingStep.PROVIDER
            else -> OnboardingStep.entries.firstOrNull { it.name == serialized } ?: OnboardingStep.LANGUAGE
        }
        return if (step == OnboardingStep.LINUX && !linuxOffered) OnboardingStep.PROVIDER else step
    }

    private fun persist(state: OnboardingState): Boolean = preferences.edit().apply {
        putString(KEY_STEP, state.step.name)
        putNullable(KEY_LANGUAGE, state.languageCode)
        putNullable(KEY_PROVIDER, state.providerId)
        putNullable(KEY_MODEL, state.modelId)
        putNullable(KEY_AGENT, state.agentId)
        putNullable(KEY_SESSION, state.sessionId)
    }.commit()

    private fun SharedPreferences.Editor.putNullable(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    companion object {
        const val PREFERENCES_NAME = "nexara_onboarding"
        private const val LEGACY_PREFERENCES_NAME = "nexara_prefs"
        private const val LEGACY_COMPLETED_KEY = "has_shown_welcome"
        private const val LEGACY_CONNECTION_STEP = "CONNECTION"
        private const val KEY_STEP = "step"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROVIDER = "provider_id"
        private const val KEY_MODEL = "model_id"
        private const val KEY_AGENT = "agent_id"
        private const val KEY_SESSION = "session_id"
        private val SUPPORTED_LANGUAGES = setOf("en", "zh")
    }
}
