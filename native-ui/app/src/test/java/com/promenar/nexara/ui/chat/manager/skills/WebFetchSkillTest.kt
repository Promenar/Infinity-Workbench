package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WebFetchSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId: String = "s1"
        override val agentId: String = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid: String = "root-1"
    }

    @Test
    fun `blocks localhost target before sending request`() = runTest {
        var called = false
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    called = true
                    error("web_fetch should block localhost before HTTP request")
                }
            }
        }
        val skill = WebFetchSkill(client)

        val result = skill.execute(mapOf("url" to "http://127.0.0.1:8080/private"), context)

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Blocked URL")
        assertThat(called).isFalse()
    }

    @Test
    fun `blocks private network target before sending request`() = runTest {
        var called = false
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    called = true
                    error("web_fetch should block private network before HTTP request")
                }
            }
        }
        val skill = WebFetchSkill(client)

        val result = skill.execute(mapOf("url" to "http://192.168.1.10/page"), context)

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Blocked URL")
        assertThat(called).isFalse()
    }
}
