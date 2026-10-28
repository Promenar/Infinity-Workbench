package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueryRewriteModelRouteTest {
    @Test
    fun `单独设置优先于快速模型与主模型`() {
        assertThat(resolveQueryRewriteModel(" provider::rewrite ", "quick", "main"))
            .isEqualTo("provider::rewrite")
    }

    @Test
    fun `未单独设置时跟随快速模型`() {
        for (override in listOf(null, "", " \t\n")) {
            assertThat(resolveQueryRewriteModel(override, " quick ", "main")).isEqualTo("quick")
        }
    }

    @Test
    fun `快速模型也未设置时回退主模型`() {
        for (quick in listOf(null, "", " \t\n")) {
            assertThat(resolveQueryRewriteModel(" ", quick, " main ")).isEqualTo("main")
        }
    }

    @Test
    fun `所有路由为空时返回null`() {
        assertThat(resolveQueryRewriteModel(null, null, null)).isNull()
        assertThat(resolveQueryRewriteModel(" ", "\t", "\n")).isNull()
    }
}
