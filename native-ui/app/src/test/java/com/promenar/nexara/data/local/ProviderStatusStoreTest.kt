package com.promenar.nexara.data.local

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbeResult
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProviderStatusStoreTest {
    private lateinit var preferences: SharedPreferences
    private var now = 1_000L

    @Before
    fun setUp() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("provider-status-test", 0)
        preferences.edit().clear().commit()
    }

    @Test
    fun `无记录时返回空状态且不把查询写入状态表`() {
        val store = store()
        assertThat(store.status("unknown")).isEqualTo(ProviderStatus())
        assertThat(store.statuses.value).isEmpty()
        assertThat(preferences.all).isEmpty()
    }

    @Test
    fun `三种连接结果同步发布并在重建后读回且不保存服务端原文`() {
        val store = store()
        store.recordConnectionTest("success", ProviderConnectionProbeResult.Success)
        now = 2_000L
        store.recordConnectionTest("unsupported", ProviderConnectionProbeResult.Unsupported)
        now = 3_000L
        store.recordConnectionTest(
            "failed",
            ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
                statusCode = 401,
                detail = "服务端原文不应落盘",
            ),
        )

        val expected = mapOf(
            "success" to ProviderStatus(ProviderConnectionRecord(ProviderConnectionOutcome.SUCCESS, testedAtMillis = 1_000L)),
            "unsupported" to ProviderStatus(ProviderConnectionRecord(ProviderConnectionOutcome.UNSUPPORTED, testedAtMillis = 2_000L)),
            "failed" to ProviderStatus(
                ProviderConnectionRecord(
                    ProviderConnectionOutcome.FAILED,
                    ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
                    401,
                    3_000L,
                ),
            ),
        )
        assertThat(store.statuses.value).containsExactlyEntriesIn(expected)
        assertThat(store().statuses.value).containsExactlyEntriesIn(expected)
        assertThat(preferences.all.toString()).doesNotContain("服务端原文不应落盘")
        assertThat(preferences.all.toString()).doesNotContain("detail")
    }

    @Test
    fun `没有HTTP状态码的失败可读回且成功覆盖会清除失败字段`() {
        val store = store()
        store.recordConnectionTest(
            "provider",
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE),
        )
        assertThat(store().status("provider").lastConnection).isEqualTo(
            ProviderConnectionRecord(
                ProviderConnectionOutcome.FAILED,
                ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE,
                testedAtMillis = 1_000L,
            ),
        )
        now = 2_000L
        store.recordConnectionTest("provider", ProviderConnectionProbeResult.Success)
        assertThat(store().status("provider").lastConnection).isEqualTo(
            ProviderConnectionRecord(ProviderConnectionOutcome.SUCCESS, testedAtMillis = 2_000L),
        )
    }

    @Test
    fun `同步与连接记录在两个写入顺序中互不覆盖并可读回`() {
        val store = store()
        store.recordModelSync("sync-first")
        assertThat(store.status("sync-first")).isEqualTo(ProviderStatus(lastModelSyncAtMillis = 1_000L))
        now = 2_000L
        store.recordConnectionTest("sync-first", ProviderConnectionProbeResult.Unsupported)
        store.recordConnectionTest("connection-first", ProviderConnectionProbeResult.Success)
        now = 3_000L
        store.recordModelSync("connection-first")
        store.recordModelSync("sync-only")

        val restored = store()
        assertThat(restored.status("sync-first")).isEqualTo(
            ProviderStatus(
                ProviderConnectionRecord(ProviderConnectionOutcome.UNSUPPORTED, testedAtMillis = 2_000L),
                1_000L,
            ),
        )
        assertThat(restored.status("connection-first")).isEqualTo(
            ProviderStatus(
                ProviderConnectionRecord(ProviderConnectionOutcome.SUCCESS, testedAtMillis = 2_000L),
                3_000L,
            ),
        )
        assertThat(restored.status("sync-only")).isEqualTo(ProviderStatus(lastModelSyncAtMillis = 3_000L))
    }

    @Test
    fun `清除连接记录保留同步时间且只有连接记录时整条移除`() {
        val store = store()
        store.recordConnectionTest("both", ProviderConnectionProbeResult.Success)
        store.recordModelSync("both")
        store.recordConnectionTest("only", ProviderConnectionProbeResult.Unsupported)

        store.clearConnectionTest("both")
        store.clearConnectionTest("only")
        store.clearConnectionTest("missing")

        assertThat(store.statuses.value.keys).containsExactly("both")
        assertThat(store().status("both")).isEqualTo(ProviderStatus(lastModelSyncAtMillis = 1_000L))
        assertThat(store().status("only")).isEqualTo(ProviderStatus())
    }

    @Test
    fun `删除同时清除连接与同步记录并保留其它提供商`() {
        val store = store()
        store.recordConnectionTest("removed", ProviderConnectionProbeResult.Success)
        store.recordModelSync("removed")
        store.recordModelSync("kept")
        store.remove("removed")

        assertThat(store.statuses.value.keys).containsExactly("kept")
        assertThat(store().status("removed")).isEqualTo(ProviderStatus())
        assertThat(store().status("kept").lastModelSyncAtMillis).isEqualTo(1_000L)
        store.remove("kept")
        assertThat(store().statuses.value).isEmpty()
    }

    @Test
    fun `清理孤儿只在存在孤儿时apply且不存在的删除不写盘`() {
        val counting = CountingPreferences(preferences)
        val store = ProviderStatusStore(counting) { now }
        store.recordModelSync("kept")
        store.recordConnectionTest("orphan", ProviderConnectionProbeResult.Unsupported)
        val before = counting.applyCount
        store.retainOnly(setOf("kept"))

        assertThat(counting.applyCount).isEqualTo(before + 1)
        assertThat(store.statuses.value.keys).containsExactly("kept")
        assertThat(store().statuses.value.keys).containsExactly("kept")
        store.retainOnly(setOf("kept", "not-recorded"))
        store.remove("missing")
        assertThat(counting.applyCount).isEqualTo(before + 1)
        assertThat(counting.commitCount).isEqualTo(0)
        store.retainOnly(emptySet())
        assertThat(store().statuses.value).isEmpty()
    }

    @Test
    fun `稳定ID可含标点与中文且未知ID安全接受记录`() {
        val store = store()
        listOf("default", "未保存::提供商/a_b", "", "token-provider").forEach { id ->
            store.recordModelSync(id)
            store.recordConnectionTest(id, ProviderConnectionProbeResult.Success)
        }
        assertThat(store().statuses.value.keys).containsExactly("default", "未保存::提供商/a_b", "", "token-provider")
    }

    @Test
    fun `未知连接结果或失败原因作为无连接记录且不影响其它提供商`() {
        writeRaw("""{
            "outcome":{"lastConnection":{"outcome":"FUTURE","testedAtMillis":1000}},
            "reason":{"lastConnection":{"outcome":"FAILED","failure":"FUTURE","testedAtMillis":1000}},
            "missing":{"lastConnection":{"outcome":"FAILED","testedAtMillis":1000}},
            "valid":{"lastModelSyncAtMillis":2000}
        }""")
        assertThat(store().statuses.value).containsExactly("valid", ProviderStatus(lastModelSyncAtMillis = 2_000L))
    }

    @Test
    fun `非数字时间及状态码作为无记录且独立字段保留`() {
        writeRaw("""{
            "time":{"lastConnection":{"outcome":"SUCCESS","testedAtMillis":"bad"},"lastModelSyncAtMillis":2000},
            "sync":{"lastModelSyncAtMillis":"bad"},
            "code":{"lastConnection":{"outcome":"FAILED","failure":"AUTHENTICATION_REJECTED","statusCode":"bad","testedAtMillis":1000}},
            "shape":{"lastConnection":[],"lastModelSyncAtMillis":3000},
            "overflow":{"lastModelSyncAtMillis":9223372036854775808}
        }""")
        assertThat(store().statuses.value).containsExactly(
            "time", ProviderStatus(lastModelSyncAtMillis = 2_000L),
            "shape", ProviderStatus(lastModelSyncAtMillis = 3_000L),
        )
    }

    @Test
    fun `损坏JSON及错误偏好类型不崩溃且后续可正常记录`() {
        listOf("not-json", "[]", "null", "{broken").forEach { raw ->
            writeRaw(raw)
            assertThat(store().statuses.value).isEmpty()
        }
        preferences.edit().putInt("statuses", 123).commit()
        val store = store()
        assertThat(store.statuses.value).isEmpty()
        store.recordModelSync("recovered")
        assertThat(store().status("recovered").lastModelSyncAtMillis).isEqualTo(1_000L)
    }

    @Test
    fun `不同线程同时记录不会丢失同一提供商的连接或同步时间`() {
        val store = store()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 4).map { worker ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(20) { index ->
                        if (worker % 2 == 0) store.recordModelSync("provider-$index")
                        else store.recordConnectionTest("provider-$index", ProviderConnectionProbeResult.Success)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertThat(store.statuses.value).hasSize(20)
            store().statuses.value.values.forEach { status ->
                assertThat(status).isEqualTo(
                    ProviderStatus(
                        ProviderConnectionRecord(ProviderConnectionOutcome.SUCCESS, testedAtMillis = 1_000L),
                        1_000L,
                    ),
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun store() = ProviderStatusStore(preferences) { now }

    private fun writeRaw(value: String) {
        preferences.edit().putString("statuses", value).commit()
    }

    private class CountingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var applyCount = 0
        var commitCount = 0

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                    editor.putString(key, value)
                }

                override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }

                override fun apply() {
                    applyCount++
                    editor.apply()
                }

                override fun commit(): Boolean {
                    commitCount++
                    return editor.commit()
                }
            }
        }
    }
}
