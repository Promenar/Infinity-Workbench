package com.promenar.nexara

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MainActivityStartupGateWiringTest {
    private val source = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/MainActivity.kt")
    ).toString(Charsets.UTF_8)

    @Test
    fun `activity collects startup state before reading welcome preferences or building nav graph`() {
        val collect = source.indexOf("startupState.collectAsStateWithLifecycle()")
        val gate = source.indexOf("StartupGate(")
        val welcome = source.indexOf("has_shown_welcome")
        val nav = source.indexOf("NexaraNavGraph(")

        assertThat(collect).isAtLeast(0)
        assertThat(gate).isGreaterThan(collect)
        assertThat(welcome).isGreaterThan(gate)
        assertThat(nav).isGreaterThan(welcome)
    }

    @Test
    fun `share intents stay cached until startup becomes ready`() {
        val onCreate = functionBody("override fun onCreate(savedInstanceState: Bundle?)")
        val onNewIntent = functionBody("override fun onNewIntent(intent: Intent)")
        val consume = functionBody("private fun consumePendingIntentIfReady()")

        assertThat(onCreate).contains("pendingIntent = intent")
        assertThat(onCreate).doesNotContain("handleIntent(intent)")
        assertThat(onNewIntent).contains("pendingIntent = intent")
        assertThat(onNewIntent).doesNotContain("handleIntent(intent)")
        assertThat(consume).contains("BackupStartupState.Ready")
        assertThat(consume).contains("handleIntent(pending)")
    }

    private fun functionBody(signature: String): String {
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
