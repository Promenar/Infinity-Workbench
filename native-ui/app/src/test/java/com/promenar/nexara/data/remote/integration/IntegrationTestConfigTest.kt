package com.promenar.nexara.data.remote.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationTestConfigTest {
    @Test
    fun `仅查询允许的六个环境变量`() {
        val queried = linkedSetOf<String>()
        val result = IntegrationTestConfig.load { name ->
            queried += name
            "configured"
        }

        assertTrue(result is IntegrationTestConfigLoadResult.Available)
        assertEquals(IntegrationTestConfig.ENVIRONMENT_NAMES, queried.toList())
        (result as IntegrationTestConfigLoadResult.Available).config.close()
    }

    @Test
    fun `任一配置缺失时返回通用不可用状态`() {
        val result = IntegrationTestConfig.load { name ->
            if (name == IntegrationTestConfig.ENVIRONMENT_NAMES.last()) null else "configured"
        }

        assertTrue(result === IntegrationTestConfigLoadResult.Unavailable)
    }

    @Test
    fun `配置描述与关闭操作不会泄露字段值`() {
        val marker = "sensitive-marker"
        val result = IntegrationTestConfig.load { marker } as IntegrationTestConfigLoadResult.Available
        val config = result.config

        assertFalse(config.toString().contains(marker))
        config.close()
        assertTrue(config.apiKey.all { it == '\u0000' })
    }
}
