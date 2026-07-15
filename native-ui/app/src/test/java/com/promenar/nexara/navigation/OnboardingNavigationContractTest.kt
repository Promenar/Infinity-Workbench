package com.promenar.nexara.navigation

import com.promenar.nexara.onboarding.OnboardingStep
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class OnboardingNavigationContractTest {
    private val navGraph = read("app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt")
    private val mainActivity = read("app/src/main/java/com/promenar/nexara/MainActivity.kt")

    @Test
    fun `启动目的地只取Activity创建时的引导快照而不随首聊完成重建导航图`() {
        assertThat(initialOnboardingDestination(OnboardingStep.LANGUAGE))
            .isEqualTo(NavDestinations.WELCOME)
        assertThat(initialOnboardingDestination(OnboardingStep.COMPLETED))
            .isEqualTo(NavDestinations.MAIN_TAB_SCAFFOLD)
        assertThat(mainActivity).contains("remember(onboardingStore)")
        assertThat(mainActivity).contains("onboardingStore.state.value.step")
        assertThat(mainActivity).doesNotContain("onboardingState.step == OnboardingStep.COMPLETED")
    }

    @Test
    fun `首聊成功只持久化完成状态而不把当前聊天页替换成主界面`() {
        assertThat(navGraph).doesNotContain("LaunchedEffect(onboardingState.step)")
    }

    @Test
    fun `打开首次聊天前先建立主界面作为返回栈基座`() {
        val firstChatHandler = functionBody(navGraph, "onOpenFirstChat =")

        assertThat(firstChatHandler).contains("openOnboardingFirstChat")
        assertThat(firstChatHandler).doesNotContain("navController.navigate(")
    }

    private fun read(path: String) = Files.readAllBytes(Path.of(path)).toString(Charsets.UTF_8)

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }
}
