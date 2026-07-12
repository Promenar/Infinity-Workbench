package com.promenar.nexara.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OnboardingStep {
    LANGUAGE,
    PROVIDER,
    CONNECTION,
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

/**
 * 首次引导的持久检查点。只有当前步骤的成功事件可以推进，失败事件保持原步骤，
 * 因而 Activity 旋转或进程重建后不会跳过尚未完成的业务动作。
 */
class OnboardingStateStore private constructor(
    private val preferences: SharedPreferences,
    private val legacyPreferences: SharedPreferences?,
) {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    internal constructor(preferences: SharedPreferences) : this(preferences, null)

    private val lock = Any()
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun selectLanguage(languageCode: String): Boolean {
        if (languageCode !in SUPPORTED_LANGUAGES) return false
        return transition(OnboardingStep.LANGUAGE) { current ->
            current.copy(step = OnboardingStep.PROVIDER, languageCode = languageCode)
        }
    }

    fun recordProviderSaved(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        return transition(OnboardingStep.PROVIDER) { current ->
            current.copy(step = OnboardingStep.CONNECTION, providerId = providerId)
        }
    }

    fun recordConnectionResult(providerId: String, succeeded: Boolean): Boolean {
        if (!succeeded) return false
        return transition(OnboardingStep.CONNECTION) { current ->
            if (current.providerId != providerId) null else current.copy(step = OnboardingStep.MODEL)
        }
    }

    fun selectModel(modelId: String?): Boolean {
        val normalized = modelId?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return transition(OnboardingStep.MODEL) { current ->
            current.copy(step = OnboardingStep.AGENT, modelId = normalized)
        }
    }

    fun returnToConnection(): Boolean = transition(OnboardingStep.MODEL) { current ->
        current.copy(step = OnboardingStep.CONNECTION, modelId = null)
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
            step = persistedStep?.let { serialized ->
                OnboardingStep.entries.firstOrNull { it.name == serialized }
            } ?: OnboardingStep.LANGUAGE,
            languageCode = preferences.getString(KEY_LANGUAGE, null),
            providerId = preferences.getString(KEY_PROVIDER, null),
            modelId = preferences.getString(KEY_MODEL, null),
            agentId = preferences.getString(KEY_AGENT, null),
            sessionId = preferences.getString(KEY_SESSION, null),
        )
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
        private const val KEY_STEP = "step"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROVIDER = "provider_id"
        private const val KEY_MODEL = "model_id"
        private const val KEY_AGENT = "agent_id"
        private const val KEY_SESSION = "session_id"
        private val SUPPORTED_LANGUAGES = setOf("en", "zh")
    }
}
