package com.promenar.nexara.ui.common

import android.content.ContentResolver
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamAnimationSettingsTest {
    @Test
    fun `多个正文订阅同一动画状态且上游只观察一次`() = runTest {
        val source = MutableStateFlow(true)
        var observations = 0
        val settings = StreamAnimationSettings(backgroundScope) {
            flow {
                observations++
                emitAll(source)
            }
        }
        val resolver = mockk<ContentResolver>()

        val first = settings.state(resolver)
        val second = settings.state(resolver)
        runCurrent()
        assertEquals(1, observations)
        assertSame(first, second)
        assertTrue(first.value == true)

        source.value = false
        runCurrent()
        assertFalse(first.value ?: true)
        assertFalse(second.value ?: true)
    }
}
