package com.promenar.nexara.ui.renderer

import android.os.SystemClock
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class RichContentThemeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun themeChangeReloadsWebContentWithoutRecreatingActivity() {
        val darkTheme = mutableStateOf(true)
        val webView = AtomicReference<WebView>()

        rule.setContent {
            NexaraTheme(
                preferences = NexaraThemePreferences(
                    mode = if (darkTheme.value) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
                    colorSource = NexaraColorSource.NEXARA,
                ),
            ) {
                RichContentWebView(
                    html = "<html><head></head><body>Theme probe</body></html>",
                    onWebViewCreated = webView::set,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) { webView.get() != null }
        val activity = rule.activity
        waitForBodyBackground(webView.get(), "rgb(19, 19, 21)")

        rule.runOnUiThread { darkTheme.value = false }
        rule.waitForIdle()

        waitForBodyBackground(webView.get(), "rgb(251, 248, 251)")
        assertThat(rule.activity).isSameInstanceAs(activity)
    }

    private fun waitForBodyBackground(webView: WebView, expected: String) {
        var actual = ""
        repeat(30) {
            actual = evaluate(webView, "getComputedStyle(document.body).backgroundColor")
            if (actual == expected) return
            SystemClock.sleep(100)
        }
        assertThat(actual).isEqualTo(expected)
    }

    private fun evaluate(webView: WebView, script: String): String {
        val result = AtomicReference("")
        val latch = CountDownLatch(1)
        rule.runOnUiThread {
            webView.evaluateJavascript(script) {
                result.set(it.trim('"'))
                latch.countDown()
            }
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        return result.get()
    }
}
