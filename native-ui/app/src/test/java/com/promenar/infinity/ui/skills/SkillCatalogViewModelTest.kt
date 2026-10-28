package com.promenar.infinity.ui.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.ParsedSkillDocument
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillSnapshot
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.data.skills.SkillWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspace
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillCatalogViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun skill(name: String, scope: SkillScope = SkillScope.USER, status: SkillStatus = SkillStatus.ENABLED) = DiscoveredSkill(
        id = if (scope == SkillScope.USER) "user:$name" else "project:ws:$name",
        scope = scope,
        name = name,
        description = "demo",
        displayName = null,
        shortDescription = null,
        virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
        files = emptyList(),
        status = status,
        modifiedSinceConfirmed = false,
        allowImplicitInvocation = true,
        brokenReason = null,
        metadata = null,
    )

    private class FakeCatalog : SkillCatalog {
        override val userRoot = File("/tmp/skills")
        override val revision = MutableStateFlow(0L)
        val scanned = mutableListOf<SkillWorkspace?>()
        var skills: List<DiscoveredSkill> = emptyList()
        var failMutation = false
        val enabledCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot {
            scanned += workspace
            return SkillSnapshot(workspace, skills.filter { workspace != null || it.scope == SkillScope.USER })
        }
        override suspend fun readDocument(skill: DiscoveredSkill): ParsedSkillDocument? = null
        override suspend fun readFile(skill: DiscoveredSkill, path: String): String = "# ${skill.name}"
        override suspend fun setEnabled(skill: DiscoveredSkill, enabled: Boolean) {
            if (failMutation) throw IllegalStateException("技能待信任")
            enabledCalls += skill.id to enabled
            revision.value++
        }
        override suspend fun trust(skill: DiscoveredSkill) { revision.value++ }
        override suspend fun acknowledgeModified(skill: DiscoveredSkill) { revision.value++ }
        override suspend fun delete(skill: DiscoveredSkill) { revision.value++ }
    }

    private fun directory(id: String): SessionWorkspace.Directory {
        val entity = mockk<WorkspaceEntity>()
        every { entity.id } returns id
        return SessionWorkspace.Directory(entity, File("/tmp/ws-$id"))
    }

    @Test
    fun `设置页只扫描用户级技能`() = runTest(dispatcher) {
        val catalog = FakeCatalog().apply { skills = listOf(skill("a"), skill("p", SkillScope.PROJECT)) }
        val viewModel = SkillCatalogViewModel(catalog) { error("未绑定会话时不解析工作区") }
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.bind(null)
        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.projectAvailable).isFalse()
        assertThat(state.snapshot!!.skills.map { it.name }).containsExactly("a")
        assertThat(catalog.scanned).containsExactly(null)
    }

    @Test
    fun `目录型工作区会话同时扫描项目级技能`() = runTest(dispatcher) {
        val catalog = FakeCatalog().apply { skills = listOf(skill("a"), skill("p", SkillScope.PROJECT)) }
        val viewModel = SkillCatalogViewModel(catalog) { directory("ws1") }
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.bind("s1")
        val state = viewModel.state.value
        assertThat(state.projectAvailable).isTrue()
        assertThat(state.snapshot!!.project.map { it.name }).containsExactly("p")
        assertThat(catalog.scanned.last()).isEqualTo(SkillWorkspace("ws1", File("/tmp/ws-ws1")))
    }

    @Test
    fun `私有工作区或解析失败时不提供项目级技能`() = runTest(dispatcher) {
        val catalog = FakeCatalog()
        val privateVm = SkillCatalogViewModel(catalog) { SessionWorkspace.Private }
        backgroundScope.launch { privateVm.state.collect {} }
        privateVm.bind("s1")
        assertThat(privateVm.state.value.projectAvailable).isFalse()

        val failingVm = SkillCatalogViewModel(catalog) { throw IllegalStateException("不可用") }
        backgroundScope.launch { failingVm.state.collect {} }
        failingVm.bind("s2")
        assertThat(failingVm.state.value.projectAvailable).isFalse()
        assertThat(catalog.scanned.takeLast(2)).containsExactly(null, null)
    }

    @Test
    fun `管理操作后重扫并在失败时给出错误`() = runTest(dispatcher) {
        val catalog = FakeCatalog().apply { skills = listOf(skill("a")) }
        val viewModel = SkillCatalogViewModel(catalog) { SessionWorkspace.Private }
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.bind("s1")
        val before = catalog.scanned.size

        viewModel.setEnabled(catalog.skills.single(), false).join()
        assertThat(catalog.enabledCalls).containsExactly("user:a" to false)
        assertThat(catalog.scanned.size).isGreaterThan(before)

        catalog.failMutation = true
        viewModel.setEnabled(catalog.skills.single(), true).join()
        assertThat(viewModel.error.value).isEqualTo("技能待信任")
        viewModel.consumeError()
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `查看原文读取 SKILL_md`() = runTest(dispatcher) {
        val viewModel = SkillCatalogViewModel(FakeCatalog()) { SessionWorkspace.Private }
        assertThat(viewModel.loadDocument(skill("demo"))).isEqualTo("# demo")
    }
}
