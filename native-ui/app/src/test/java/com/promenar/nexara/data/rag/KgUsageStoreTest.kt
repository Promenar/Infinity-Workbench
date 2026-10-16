package com.promenar.nexara.data.rag

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class KgUsageStoreTest {
    private lateinit var preferences: SharedPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("kg-usage-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    }

    @After
    fun cleanup() { preferences.edit().clear().commit() }

    @Test
    fun `实际用量跨存储实例恢复并在清空后归零`() = runTest {
        val store = PreferencesKgUsageStore(preferences)
        store.record(KgUsageRecord("owner::model", KgTokenUsage(17, 7)))
        store.record(KgUsageRecord("owner::model", KgTokenUsage(3, 2, estimated = true)))

        val restored = PreferencesKgUsageStore(preferences).snapshot()
        assertThat(restored.usage).isEqualTo(KgTokenUsage(20, 9, estimated = true))
        assertThat(restored.calls).isEqualTo(2)
        assertThat(restored.records).hasSize(1)

        store.clear()
        assertThat(PreferencesKgUsageStore(preferences).snapshot().calls).isEqualTo(0)
    }

    @Test
    fun `模型和日趋势条目有界但累计用量不因归档丢失`() = runTest {
        val store = PreferencesKgUsageStore(preferences)
        val start = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
        repeat(300) { index ->
            store.record(KgUsageRecord("owner::model-$index", KgTokenUsage(10, 2), createdAt = start - index * 86_400_000L))
        }

        val snapshot = store.snapshot()
        assertThat(snapshot.records.size).isAtMost(256)
        assertThat(snapshot.dailyUsage).hasSize(90)
        assertThat(snapshot.calls).isEqualTo(300)
        assertThat(snapshot.usage).isEqualTo(KgTokenUsage(3_000, 600))
    }
}
