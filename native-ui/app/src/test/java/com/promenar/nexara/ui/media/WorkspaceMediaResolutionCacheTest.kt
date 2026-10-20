package com.promenar.nexara.ui.media

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.media.MediaResolution
import com.promenar.nexara.domain.media.MediaUnavailableReason
import org.junit.jupiter.api.Test

class WorkspaceMediaResolutionCacheTest {
    private var now = 0L
    private val cache = WorkspaceMediaResolutionCache(maxEntries = 2, revalidateAfterMs = 100, clock = { now })
    private fun value(path: String) = ResolvedWorkspaceMedia(MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND), null)

    @Test
    fun `按会话与路径缓存，命中后在复核期内不再解析`() {
        assertThat(cache.needsResolve("s1", "/a")).isTrue()
        cache.put("s1", "/a", value("/a"))
        assertThat(cache.get("s1", "/a")).isEqualTo(value("/a"))
        assertThat(cache.get("s2", "/a")).isNull()
        assertThat(cache.needsResolve("s1", "/a")).isFalse()

        now = 100
        assertThat(cache.needsResolve("s1", "/a")).isTrue()
        // 过期条目仍可立即显示，避免流式重组时闪烁。
        assertThat(cache.get("s1", "/a")).isNotNull()
    }

    @Test
    fun `超过容量时淘汰最久未用的条目`() {
        cache.put("s1", "/a", value("/a"))
        cache.put("s1", "/b", value("/b"))
        cache.get("s1", "/a")
        cache.put("s1", "/c", value("/c"))

        assertThat(cache.get("s1", "/b")).isNull()
        assertThat(cache.get("s1", "/a")).isNotNull()
        assertThat(cache.get("s1", "/c")).isNotNull()
        cache.invalidate("s1", "/a")
        assertThat(cache.get("s1", "/a")).isNull()
    }
}
