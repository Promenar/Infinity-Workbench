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
    fun `share intents enter one queue path and stay cached until startup becomes ready`() {
        val onCreate = functionBody("override fun onCreate(savedInstanceState: Bundle?)")
        val onNewIntent = functionBody("override fun onNewIntent(intent: Intent)")
        val consume = functionBody("private fun consumeShareIntentsIfReady()")

        assertThat(source).contains("by viewModels<ShareIntentViewModel>()")
        assertThat(onCreate).contains("restoreConsumedState(savedInstanceState)")
        assertThat(onCreate).contains("enqueueShareIntent(intent)")
        assertThat(onNewIntent).contains("enqueueShareIntent(intent)")
        assertThat(consume).contains("BackupStartupState.Ready")
        assertThat(consume).contains("shareIntentQueue.consumeAll")
    }

    @Test
    fun `only consumed state is saved and every share payload is immediately replaced`() {
        val save = functionBody("override fun onSaveInstanceState(outState: Bundle)")
        val enqueue = functionBody("private fun enqueueShareIntent(candidate: Intent?)")

        assertThat(save).contains("saveConsumedState(outState)")
        assertThat(save).doesNotContain("pending")
        assertThat(enqueue).contains("ShareEnqueueResult.RejectedInvalid")
        assertThat(enqueue).contains("ShareEnqueueResult.RejectedCapacity")
        assertThat(enqueue).contains("setIntent(Intent(this, MainActivity::class.java)")
        assertThat(enqueue).contains("Intent.ACTION_MAIN")
        assertThat(enqueue).contains("R.string.share_intent_invalid")
        assertThat(enqueue).contains("R.string.share_intent_queue_full")
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
