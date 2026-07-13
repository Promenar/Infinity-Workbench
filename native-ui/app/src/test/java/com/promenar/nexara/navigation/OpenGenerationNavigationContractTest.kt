package com.promenar.nexara.navigation

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class OpenGenerationNavigationContractTest {
    private val activity = read("app/src/main/java/com/promenar/nexara/MainActivity.kt")
    private val navGraph = read("app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt")

    @Test
    fun `冷启动与singleTask新Intent都进入一次性OPEN路由`() {
        assertThat(functionBody(activity, "override fun onCreate(savedInstanceState: Bundle?)"))
            .contains("routeOpenGenerationIntent(intent)")
        assertThat(functionBody(activity, "override fun onNewIntent(intent: Intent)"))
            .contains("routeOpenGenerationIntent(intent)")
        val route = functionBody(activity, "private fun routeOpenGenerationIntent(candidate: Intent?)")
        assertThat(route).contains("appIntentRouter.offer(candidate)")
        assertThat(route).contains("setIntent(cleanMainIntent())")
    }

    @Test
    fun `OPEN仅在StartupGate内且onboarding完成后导航并确认一次`() {
        assertThat(activity.indexOf("StartupGate(")).isLessThan(activity.indexOf("NexaraNavGraph("))
        val effect = functionBody(
            navGraph,
            "LaunchedEffect(openGenerationRequest, onboardingState.step)",
        )
        assertThat(effect.indexOf("OnboardingStep.COMPLETED")).isAtLeast(0)
        assertThat(effect.indexOf("navController.navigate")).isGreaterThan(effect.indexOf("OnboardingStep.COMPLETED"))
        assertThat(effect.indexOf("onOpenGenerationConsumed"))
            .isGreaterThan(effect.indexOf("navController.navigate"))
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
