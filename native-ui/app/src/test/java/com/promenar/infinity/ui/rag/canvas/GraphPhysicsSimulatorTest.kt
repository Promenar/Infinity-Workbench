package com.promenar.infinity.ui.rag.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.rag.GraphNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GraphPhysicsSimulatorTest {
    @Test
    fun `相同实体输入得到确定性的初始布局`() = runTest {
        val nodes = listOf(
            GraphNode("a", "甲", "concept", 0f, 0f),
            GraphNode("b", "乙", "concept", 0f, 0f),
        )
        val first = GraphPhysicsSimulator(backgroundScope)
        val second = GraphPhysicsSimulator(backgroundScope)
        first.setData(nodes, emptyList())
        second.setData(nodes, emptyList())

        assertThat(first.nodesState.map { it.x to it.y })
            .containsExactlyElementsIn(second.nodesState.map { it.x to it.y }).inOrder()
        first.clear()
        second.clear()
    }

    @Test
    fun `不可见页面停止布局并在返回时恢复未收敛布局`() = runTest {
        val simulator = GraphPhysicsSimulator(backgroundScope, computationDispatcher = StandardTestDispatcher(testScheduler))
        simulator.setPageVisible(false)
        simulator.setData(listOf(GraphNode("a", "甲", "concept", 0f, 0f)), emptyList())
        val initial = simulator.nodesState
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(simulator.isSimulating).isFalse()
        assertThat(simulator.nodesState).isEqualTo(initial)

        simulator.setPageVisible(true)
        assertThat(simulator.isSimulating).isTrue()
        advanceTimeBy(32)
        runCurrent()
        assertThat(simulator.nodesState).isNotEqualTo(initial)
        simulator.setPageVisible(false)
        val paused = simulator.nodesState
        advanceTimeBy(100)
        runCurrent()
        assertThat(simulator.nodesState).isEqualTo(paused)
        assertThat(simulator.isSimulating).isFalse()
        simulator.clear()
    }

    @Test
    fun `收敛布局与空图停止计算且页面返回不唤醒已收敛布局`() = runTest {
        val simulator = GraphPhysicsSimulator(backgroundScope, kr = 0f, ka = 0f, kg = 0f,
            computationDispatcher = StandardTestDispatcher(testScheduler))
        simulator.setData(emptyList(), emptyList())
        assertThat(simulator.isSimulating).isFalse()
        simulator.setData(listOf(GraphNode("a", "甲", "concept", 0f, 0f)), emptyList())
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(simulator.isSimulating).isFalse()
        simulator.setPageVisible(false)
        simulator.setPageVisible(true)
        assertThat(simulator.isSimulating).isFalse()
        simulator.clear()
    }
}
