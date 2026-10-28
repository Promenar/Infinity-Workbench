package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GitNonceRegistryTest {
    private val registry = GitNonceRegistry()

    @Test fun `nonce唯一且只在作用域关闭前有效`() {
        val first = registry.issue()
        val second = registry.issue()
        assertThat(first.nonce).isNotEqualTo(second.nonce)
        assertThat(first.nonce).hasLength(GitNonceRegistry.NONCE_LENGTH)
        assertThat(registry.resolve(first.nonce)).isSameInstanceAs(first)
        assertThat(registry.resolve(second.nonce)).isSameInstanceAs(second)

        first.close()
        assertThat(registry.resolve(first.nonce)).isNull()
        // 并发命令互不影响。
        assertThat(registry.resolve(second.nonce)).isSameInstanceAs(second)
        second.close()
        assertThat(registry.activeCount).isEqualTo(0)
    }

    @Test fun `格式错误或未知nonce无效`() {
        registry.issue()
        listOf(null, "", "short", "x".repeat(43), "!".repeat(43), "令".repeat(43), "a".repeat(44)).forEach {
            assertThat(registry.resolve(it)).isNull()
        }
    }

    @Test fun `关闭作用域同时关闭登记的连接且之后登记立即关闭`() {
        val scope = registry.issue()
        val closed = AtomicInteger()
        assertThat(scope.register(AutoCloseable { closed.incrementAndGet() })).isTrue()
        val removed = AutoCloseable { closed.addAndGet(100) }
        scope.register(removed)
        scope.unregister(removed)
        scope.close()
        assertThat(closed.get()).isEqualTo(1)
        scope.close()
        assertThat(closed.get()).isEqualTo(1)
        assertThat(scope.register(AutoCloseable { closed.addAndGet(10) })).isFalse()
        assertThat(closed.get()).isEqualTo(11)
    }
}
