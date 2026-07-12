package com.promenar.nexara.onboarding

import java.util.concurrent.atomic.AtomicBoolean

/** 串行化首次语言选择，并在状态检查点写入失败时回滚已持久化的 locale。 */
class OnboardingLanguageSelectionCoordinator(
    private val stateStore: OnboardingStateStore,
    private val currentLanguage: () -> String,
    private val persistLanguage: (String) -> Boolean,
    private val applyLanguage: (String) -> Unit,
) {
    private val inFlight = AtomicBoolean(false)

    fun select(languageCode: String): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        return try {
            val previousLanguage = currentLanguage()
            if (!persistLanguage(languageCode)) return false
            if (!stateStore.selectLanguage(languageCode)) {
                persistLanguage(previousLanguage)
                return false
            }
            applyLanguage(languageCode)
            true
        } finally {
            inFlight.set(false)
        }
    }
}
