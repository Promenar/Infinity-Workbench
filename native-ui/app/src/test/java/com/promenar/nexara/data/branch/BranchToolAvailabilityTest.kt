package com.promenar.nexara.data.branch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.DefaultSessionToolResolver
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.chat.manager.registry.*
import io.mockk.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BranchToolAvailabilityTest {
    @Test fun `普通会话可广告派发工具分支和失去父会话的分支均不可广告`() {
        val settings = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("branch_tools", Context.MODE_PRIVATE)
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools(null) } returns listOf(SessionBranchTool().toProtocolTool())
        val resolver = DefaultSessionToolResolver(settings, registry)
        val parent = Session("main", "a")
        assertThat(resolver.resolve(parent).map { it.function.name }).contains("session_branch")
        listOf(parent.copy(parentSessionId = "parent"), parent.copy(branchPlanJson = "{}"), parent.copy(parentSessionDeleted = true)).forEach {
            assertThat(resolver.resolve(it).map { tool -> tool.function.name }).doesNotContain("session_branch")
        }
    }
}
