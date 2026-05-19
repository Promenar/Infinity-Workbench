package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.PlanPatchOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextBuilderTest {
    private val testScope = TestScope()

    @Test
    fun buildContextWithNoProviders() = testScope.runTest {
        val builder = ContextBuilder()
        val session = Session(id = "s1", agentId = "a1")

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "What is AI?",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.searchContext).isEmpty()
        assertThat(result.ragContext).isEmpty()
        assertThat(result.ragReferences).isEmpty()
        assertThat(result.finalSystemPrompt).isNotEmpty()
    }

    @Test
    fun buildContextIncludesTime() = testScope.runTest {
        val builder = ContextBuilder()
        val session = Session(id = "s1", agentId = "a1")

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "What time is it?",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.finalSystemPrompt).contains("System Time")
    }

    @Test
    fun buildContextIncludesCustomPrompt() = testScope.runTest {
        val builder = ContextBuilder()
        val session = Session(id = "s1", agentId = "a1", customPrompt = "Be concise")

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "Hello",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.finalSystemPrompt).contains("Be concise")
    }

    @Test
    fun buildContextWithActiveTask() = testScope.runTest {
        val task = com.promenar.nexara.data.model.TaskState(
            id = "t1",
            title = "Build app",
            status = "in-progress",
            steps = listOf(
                com.promenar.nexara.data.model.TaskStep(id = "s1", title = "Step 1", status = "pending")
            )
        )
        val session = Session(id = "s1", agentId = "a1", activeTask = task)
        val fakeRepo = object : com.promenar.nexara.domain.repository.ITaskRepository {
            override fun observeActiveTree(sessionId: String): kotlinx.coroutines.flow.Flow<List<com.promenar.nexara.data.model.TaskStep>> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun initializePlan(sessionId: String, goal: String, tree: List<com.promenar.nexara.data.model.TaskStep>): TaskState = task
            override suspend fun updatePlan(sessionId: String, operations: List<com.promenar.nexara.domain.repository.PlanPatchOp>): TaskState = task
            override suspend fun getPlan(sessionId: String): TaskState? = task
            override suspend fun dropPlan(sessionId: String, reason: String) {}
            override fun deriveParentStatus(children: List<com.promenar.nexara.data.model.TaskStep>): String = "pending"
            override fun countLeafProgress(steps: List<com.promenar.nexara.data.model.TaskStep>): Pair<Int, Int> = Pair(0, 1)
        }
        val builder = ContextBuilder(taskRepository = fakeRepo)

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "Continue",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.finalSystemPrompt).contains("Active Task")
        assertThat(result.finalSystemPrompt).contains("Build app")
    }

    @Test
    fun buildContextWithWebSearch() = testScope.runTest {
        val webSearchProvider = object : WebSearchProvider {
            override suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>> {
                return Pair("Search results for: $query", listOf(
                    com.promenar.nexara.data.model.Citation(title = "Result 1", url = "https://example.com")
                ))
            }
        }

        val builder = ContextBuilder(webSearchProvider = webSearchProvider)
        val session = Session(
            id = "s1",
            agentId = "a1",
            options = com.promenar.nexara.data.model.SessionOptions(webSearch = true)
        )

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "latest news",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.searchContext).contains("Search results for: latest news")
        assertThat(result.finalSystemPrompt).contains("Web Search Results")
        assertThat(result.citations).hasSize(1)
        assertThat(result.citations.first().title).isEqualTo("Result 1")
    }

    @Test
    fun buildContextWithRag() = testScope.runTest {
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                return Triple(
                    "RAG context",
                    listOf(RagReference(id = "r1", content = "ref content", source = "doc1")),
                    RagUsage(ragSystem = 100, isEstimated = false)
                )
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider)
        val session = Session(id = "s1", agentId = "a1", ragOptions = RagOptions(enableMemory = true))

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "tell me about X",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.ragContext).isEqualTo("RAG context")
        assertThat(result.ragReferences).hasSize(1)
        assertThat(result.ragUsage).isNotNull()
        assertThat(result.ragUsage!!.ragSystem).isEqualTo(100)
    }

    @Test
    fun buildContextRagDisabled() = testScope.runTest {
        var ragCalled = false
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                ragCalled = true
                return Triple("", emptyList(), null)
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider)
        val session = Session(id = "s1", agentId = "a1", ragOptions = RagOptions(enableMemory = false, enableDocs = false))

        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "hello",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(ragCalled).isFalse()
    }

    @Test
    fun kgContextSkippedWhenToggleOff() = testScope.runTest {
        var kgCalled = false
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): String? {
                kgCalled = true
                return "KG context"
            }
        }
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                return Triple(
                    "RAG context",
                    listOf(RagReference(id = "r1", content = "ref", source = "doc1")),
                    null
                )
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider, kgProvider = kgProvider)
        val session = Session(
            id = "s1",
            agentId = "a1",
            ragOptions = RagOptions(enableMemory = true, enableKnowledgeGraph = false)
        )

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "hello",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(kgCalled).isFalse()
        assertThat(result.finalSystemPrompt).doesNotContain("Knowledge Graph Relations")
    }

    @Test
    fun kgContextInjectedWhenToggleOn() = testScope.runTest {
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): String? {
                return "entity A -> related_to -> entity B"
            }
        }
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                return Triple(
                    "RAG context",
                    listOf(RagReference(id = "r1", content = "ref", source = "doc1")),
                    null
                )
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider, kgProvider = kgProvider)
        val session = Session(
            id = "s1",
            agentId = "a1",
            ragOptions = RagOptions(enableMemory = true, enableKnowledgeGraph = true)
        )

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "hello",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.finalSystemPrompt).contains("Knowledge Graph Relations")
        assertThat(result.finalSystemPrompt).contains("entity A")
    }

    @Test
    fun kgContextSkippedWhenRagOptionsNull() = testScope.runTest {
        var kgCalled = false
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): String? {
                kgCalled = true
                return "KG context"
            }
        }
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                return Triple(
                    "RAG context",
                    listOf(RagReference(id = "r1", content = "ref", source = "doc1")),
                    null
                )
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider, kgProvider = kgProvider)
        val session = Session(
            id = "s1",
            agentId = "a1",
            ragOptions = null
        )

        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "hello",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(kgCalled).isFalse()
    }

    @Test
    fun buildContextCleanSearchQuery() = testScope.runTest {
        var capturedQuery = ""
        val webSearchProvider = object : WebSearchProvider {
            override suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>> {
                capturedQuery = query
                return Pair("results", emptyList())
            }
        }
        val builder = ContextBuilder(webSearchProvider = webSearchProvider)
        val session = Session(
            id = "s1",
            agentId = "a1",
            options = com.promenar.nexara.data.model.SessionOptions(webSearch = true)
        )

        // 场景 1：中文前缀与后缀清洗
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "帮我搜索一下2026年人工智能最新进展，并写个总结",
            assistantMsgId = "m1",
            session = session
        ))
        assertThat(capturedQuery).isEqualTo("2026年人工智能最新进展")

        // 场景 2：英文前缀、后缀清洗与标点过滤
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "please search about the latest architectural changes in React 19, thanks!",
            assistantMsgId = "m1",
            session = session
        ))
        assertThat(capturedQuery).isEqualTo("the latest architectural changes in React 19")

        // 场景 3：英文长句多国语智能截断（在 80 个字符的单词空格边界做截断，不打碎 React）
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "please search for a comprehensive guide on building highly scalable backend architectures using Kotlin Ktor WebSockets and Postgres in 2026, thank you",
            assistantMsgId = "m1",
            session = session
        ))
        // "a comprehensive guide on building highly scalable backend architectures using Kotlin Ktor"
        // 长度为 79 字符，加上下一个单词 "WebSockets" 会超 80。因此它会安全截断到 Kotlin Ktor 之前的空格
        assertThat(capturedQuery).isEqualTo("a comprehensive guide on building highly scalable backend architectures using")

        // 场景 4：中英文疑问与助词过滤
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "请问什么是量子计算呢",
            assistantMsgId = "m1",
            session = session
        ))
        assertThat(capturedQuery).isEqualTo("量子计算")

        // 场景 5：中英文长段口语化前缀/后缀与语气词综合净化
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "你能帮我科普一下生成式AI到底是什么意思吗，谢谢你",
            assistantMsgId = "m1",
            session = session
        ))
        assertThat(capturedQuery).isEqualTo("生成式AI")

        // 场景 6：英文疑问式首尾与停用词修剪
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "tell me about the difference between quantum mechanics and classical mechanics please",
            assistantMsgId = "m1",
            session = session
        ))
        assertThat(capturedQuery).isEqualTo("quantum mechanics and classical mechanics")

        // 场景 7：极端空字符回退降级防御测试
        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "什么是",
            assistantMsgId = "m1",
            session = session
        ))
        assertThat(capturedQuery).isEqualTo("什么是")
    }
}


