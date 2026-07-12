package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.model.KgEdge
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.data.model.json
import com.promenar.nexara.domain.repository.PlanPatchOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class ContextBuilderTest {
    private val testScope = TestScope()

    @Test
    fun snapshotKgPathsAppliesStableStructuralLimitsWithoutDanglingEdges() {
        val builder = ContextBuilder()
        val paths = (0 until 40).map { pathIndex ->
            val nodes = (0 until 140).map { nodeIndex ->
                KgNode("p${pathIndex}n$nodeIndex", "node-$nodeIndex", "concept")
            }
            KgPath(
                queryKeywords = (0 until 40).map { "keyword-$it" },
                nodes = nodes,
                edges = (0 until 300).map { edgeIndex ->
                    if (edgeIndex == 0) {
                        KgEdge(nodes.first().id, "discarded-node", "dangling")
                    } else {
                        KgEdge(nodes[edgeIndex % 128].id, nodes[(edgeIndex + 1) % 128].id, "edge-$edgeIndex")
                    }
                },
                reasoning = "path-$pathIndex",
            )
        }

        val snapshot = builder.snapshotKgPaths(paths)

        assertThat(snapshot.size).isAtMost(32)
        assertThat(snapshot.map { it.reasoning })
            .containsExactlyElementsIn((0 until snapshot.size).map { "path-$it" }).inOrder()
        assertThat(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size).isAtMost(512 * 1024)
        snapshot.forEach { path ->
            assertThat(path.queryKeywords.size).isAtMost(32)
            assertThat(path.nodes.size).isAtMost(128)
            assertThat(path.edges.size).isAtMost(256)
            val nodeIds = path.nodes.map { it.id }.toSet()
            assertThat(path.edges.all { it.sourceId in nodeIds && it.targetId in nodeIds }).isTrue()
        }
    }

    @Test
    fun snapshotKgPathsStopsAtCompletePathBoundaryWithinSerializedByteLimit() {
        val builder = ContextBuilder()
        val small = KgPath(nodes = listOf(KgNode("n1", "N", "concept")), reasoning = "small")
        val oversizedNodes = (0 until 128).map { index ->
            KgNode(
                id = "huge-$index",
                label = "L".repeat(512),
                type = "T".repeat(512),
                metadata = "M".repeat(4096),
            )
        }
        val oversized = KgPath(
            nodes = oversizedNodes,
            edges = (0 until 256).map { index ->
                KgEdge(
                    sourceId = oversizedNodes[index % 128].id,
                    targetId = oversizedNodes[(index + 1) % 128].id,
                    relation = "R".repeat(512),
                )
            },
        )

        val snapshot = builder.snapshotKgPaths(listOf(small, oversized, small.copy(reasoning = "after")))
        val serializedBytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size

        assertThat(snapshot.map { it.reasoning }).containsExactly("small")
        assertThat(serializedBytes).isAtMost(512 * 1024)
    }

    @Test
    fun snapshotKgPathsTruncatesOversizedFieldsBeforeCopyingAndEncoding() {
        val huge = "🚀".repeat(400_000)
        val path = KgPath(
            queryKeywords = listOf(huge),
            nodes = listOf(KgNode(id = huge, label = huge, type = huge, metadata = huge)),
            edges = listOf(KgEdge(sourceId = huge, targetId = huge, relation = huge)),
            reasoning = huge,
        )

        val snapshot = ContextBuilder().snapshotKgPaths(listOf(path))

        assertThat(snapshot).hasSize(1)
        val trimmed = snapshot.single()
        assertThat(trimmed.queryKeywords.single().length).isAtMost(512)
        assertThat(trimmed.nodes.single().id.length).isAtMost(512)
        assertThat(trimmed.nodes.single().label.length).isAtMost(512)
        assertThat(trimmed.nodes.single().type.length).isAtMost(512)
        assertThat(trimmed.nodes.single().metadata!!.length).isAtMost(4096)
        assertThat(trimmed.edges.single().relation.length).isAtMost(512)
        assertThat(trimmed.edges.single().sourceId).isEqualTo(trimmed.nodes.single().id)
        assertThat(trimmed.edges.single().targetId).isEqualTo(trimmed.nodes.single().id)
        assertThat(trimmed.reasoning!!.length).isAtMost(4096)
        assertThat(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size).isAtMost(512 * 1024)
    }

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
    fun buildContextAppliesAgentRetrievalConfigToRagOptions() = testScope.runTest {
        var capturedOptions: RagOptions? = null
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                capturedOptions = options
                return Triple("RAG context", emptyList(), null)
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider)
        val session = Session(id = "s1", agentId = "a1", ragOptions = RagOptions(enableMemory = true, enableDocs = true))

        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "tell me about X",
            assistantMsgId = "m1",
            session = session,
            agentRetrievalConfig = AgentRetrievalConfig(
                memoryLimit = 2,
                memoryThreshold = 0.25f,
                docLimit = 3,
                docThreshold = 0.2f,
                rerankTopK = 9,
                rerankFinalK = 4,
                enableQueryRewrite = false,
                enableHybridSearch = false,
                hybridAlpha = 0.3f,
                hybridBM25Boost = 1.7f
            )
        ))

        val options = capturedOptions!!
        assertThat(options.memoryLimit).isEqualTo(2)
        assertThat(options.memoryThreshold).isEqualTo(0.25f)
        assertThat(options.docLimit).isEqualTo(3)
        assertThat(options.docThreshold).isEqualTo(0.2f)
        assertThat(options.rerankTopK).isEqualTo(9)
        assertThat(options.rerankFinalK).isEqualTo(4)
        assertThat(options.enableQueryRewrite).isFalse()
        assertThat(options.enableHybridSearch).isFalse()
        assertThat(options.hybridAlpha).isEqualTo(0.3f)
        assertThat(options.hybridBM25Boost).isEqualTo(1.7f)
    }

    @Test
    fun buildContextInjectsFullRagContextIntoSystemPrompt() = testScope.runTest {
        val longTail = "TAIL_CONTEXT_AFTER_400_CHARS"
        val longContext = "A".repeat(450) + longTail
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                return Triple(
                    longContext,
                    listOf(RagReference(id = "r1", content = "short ref", source = "doc1")),
                    RagUsage(ragSystem = 120, isEstimated = true)
                )
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider)
        val session = Session(id = "s1", agentId = "a1", ragOptions = RagOptions(enableDocs = true))

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "find the tail",
            assistantMsgId = "m1",
            session = session
        ))

        assertThat(result.finalSystemPrompt).contains("## Retrieved Context")
        assertThat(result.finalSystemPrompt).contains(longTail)
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
            ): KgContextResult {
                kgCalled = true
                return KgContextResult(context = "KG context", paths = emptyList())
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
        assertThat(result.kgPaths).isEmpty()
    }

    @Test
    fun kgContextInjectedWhenToggleOn() = testScope.runTest {
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult {
                return KgContextResult(
                    context = "entity A -> related_to -> entity B",
                    paths = emptyList()
                )
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
            ): KgContextResult {
                kgCalled = true
                return KgContextResult(context = "KG context", paths = emptyList())
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
    fun kgPathsPassedThroughToResultWhenProviderReturnsPaths() = testScope.runTest {
        val kgPath = KgPath(
            nodes = listOf(
                KgNode(id = "A", label = "A", type = "concept"),
                KgNode(id = "B", label = "B", type = "concept")
            ),
            edges = listOf(KgEdge(sourceId = "A", targetId = "B", relation = "related_to"))
        )
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult {
                return KgContextResult(
                    context = "A --[related_to]--> B",
                    paths = listOf(kgPath)
                )
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

        assertThat(result.kgPaths).hasSize(1)
        assertThat(result.kgPaths.first().edges).hasSize(1)
        assertThat(result.kgPaths.first().edges.first().relation).isEqualTo("related_to")
        assertThat(result.finalSystemPrompt).contains("Knowledge Graph Relations")
    }

    @Test
    fun kgPathsEmptyWhenProviderReturnsNull() = testScope.runTest {
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult? = null
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

        assertThat(result.kgPaths).isEmpty()
        assertThat(result.finalSystemPrompt).doesNotContain("Knowledge Graph Relations")
    }

    @Test
    fun kgContextSwallowsProviderExceptionKeepingEmptyPathsAndPromptCompatible() = testScope.runTest {
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult = throw RuntimeException("boom")
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

        assertThat(result.kgPaths).isEmpty()
        assertThat(result.finalSystemPrompt).doesNotContain("Knowledge Graph Relations")
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

    @Test
    fun kgSkippedWhenAgentRetrievalConfigOverridesSessionKnowledgeGraphTrue() = testScope.runTest {
        var kgCalled = false
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult {
                kgCalled = true
                return KgContextResult(context = "KG", paths = emptyList())
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
        // session 显式开启 KG，但 agent 配置强制关闭，必须以 agent 为准阻止 KG
        val session = Session(
            id = "s1",
            agentId = "a1",
            ragOptions = RagOptions(enableMemory = true, enableKnowledgeGraph = true)
        )

        val result = builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "hello",
            assistantMsgId = "m1",
            session = session,
            agentRetrievalConfig = AgentRetrievalConfig(enableKnowledgeGraph = false)
        ))

        assertThat(kgCalled).isFalse()
        assertThat(result.kgPaths).isEmpty()
        assertThat(result.finalSystemPrompt).doesNotContain("Knowledge Graph Relations")
    }

    @Test
    fun kgCancellationExceptionIsRethrownNotSwallowed() = testScope.runTest {
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult = throw CancellationException("parent cancelled")
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

        var caught: Throwable? = null
        try {
            builder.buildContext(ContextBuilderParams(
                sessionId = "s1",
                content = "hello",
                assistantMsgId = "m1",
                session = session
            ))
        } catch (e: Throwable) {
            caught = e
        }

        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun kgProviderPathMutationAfterBuildDoesNotLeakIntoResult() = testScope.runTest {
        val mutableNodes = mutableListOf(
            KgNode(id = "A", label = "A", type = "concept"),
            KgNode(id = "B", label = "B", type = "concept")
        )
        val mutableEdges = mutableListOf(
            KgEdge(sourceId = "A", targetId = "B", relation = "related_to")
        )
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult = KgContextResult(
                context = "A --[related_to]--> B",
                paths = listOf(KgPath(nodes = mutableNodes, edges = mutableEdges))
            )
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

        // 构建完成后 provider 侧继续修改内部可变集合，结果必须不受影响（防御性快照）
        mutableNodes.clear()
        mutableEdges.clear()

        assertThat(result.kgPaths).hasSize(1)
        assertThat(result.kgPaths.first().nodes).hasSize(2)
        assertThat(result.kgPaths.first().edges).hasSize(1)
    }

    @Test
    fun ragAndKgShareSingleEffectiveRagOptions() = testScope.runTest {
        var capturedRagOptions: RagOptions? = null
        var kgCaptured = false
        val ragProvider = object : RagProvider {
            override suspend fun retrieveContext(
                query: String,
                sessionId: String,
                options: RagOptions,
                onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
            ): Triple<String, List<RagReference>, RagUsage?> {
                capturedRagOptions = options
                return Triple(
                    "RAG context",
                    listOf(RagReference(id = "r1", content = "ref", source = "doc1")),
                    null
                )
            }
        }
        val kgProvider = object : KgProvider {
            override suspend fun extractContext(
                query: String,
                sessionId: String,
                topKResults: List<RagReference>
            ): KgContextResult {
                kgCaptured = true
                return KgContextResult(context = "KG", paths = emptyList())
            }
        }

        val builder = ContextBuilder(ragProvider = ragProvider, kgProvider = kgProvider)
        val session = Session(
            id = "s1",
            agentId = "a1",
            ragOptions = RagOptions(enableMemory = true, enableKnowledgeGraph = true)
        )

        builder.buildContext(ContextBuilderParams(
            sessionId = "s1",
            content = "hello",
            assistantMsgId = "m1",
            session = session,
            agentRetrievalConfig = AgentRetrievalConfig(enableKnowledgeGraph = true, memoryLimit = 7)
        ))

        // RAG 收到的 effective 配置带 agent 覆盖
        assertThat(capturedRagOptions).isNotNull()
        assertThat(capturedRagOptions!!.memoryLimit).isEqualTo(7)
        // KG 用的是同一份 effective 配置 → 开启 → 被调用
        assertThat(kgCaptured).isTrue()
    }
}
