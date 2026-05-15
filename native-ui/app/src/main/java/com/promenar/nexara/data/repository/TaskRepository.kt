package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.TaskNodeDao
import com.promenar.nexara.data.local.db.entity.TaskNodeEntity
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.domain.repository.PlanPatchOp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.json.JSONArray

class TaskRepository(
    private val dao: TaskNodeDao
) : ITaskRepository {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> {
        return dao.observeActiveTree(sessionId).map { entities ->
            buildTree(entities)
        }
    }

    override suspend fun initializePlan(sessionId: String, goal: String, tree: List<TaskStep>): TaskState {
        val existing = dao.getAllActiveBySession(sessionId)
        if (existing.isNotEmpty()) {
            val existingTree = buildTree(existing)
            val (done, total) = countLeafProgress(existingTree)
            val rootStep = existingTree.firstOrNull()
            return TaskState(
                id = rootStep?.id ?: "",
                title = rootStep?.title ?: goal,
                status = "conflict",
                steps = existingTree,
                currentFocusStepId = existing.find { it.status == "doing" }?.id,
                createdAt = rootStep?.createdAt ?: System.currentTimeMillis()
            )
        }

        val now = System.currentTimeMillis()
        val entities = mutableListOf<TaskNodeEntity>()
        flattenTree(tree, sessionId, parentId = null, now, entities)
        dao.upsertAll(entities)

        val firstLeaf = entities.firstOrNull { e ->
            entities.none { it.parentId == e.id }
        }
        if (firstLeaf != null) {
            dao.updateStatus(firstLeaf.id, "doing", null, now)
        }

        val saved = dao.getAllActiveBySession(sessionId)
        val builtTree = buildTree(saved)
        val (done, total) = countLeafProgress(builtTree)
        val rootStep = builtTree.firstOrNull()
        val doingNode = saved.find { it.status == "doing" }

        return TaskState(
            id = rootStep?.id ?: "",
            title = goal,
            status = deriveRootStatus(builtTree),
            steps = builtTree,
            currentFocusStepId = doingNode?.id,
            createdAt = now
        )
    }

    override suspend fun updatePlan(sessionId: String, operations: List<PlanPatchOp>): TaskState {
        val allNodes = dao.getAllActiveBySession(sessionId)
        val now = System.currentTimeMillis()

        for (op in operations) {
            when (op.action) {
                "set_status" -> {
                    val stepId = op.stepId
                        ?: throw IllegalArgumentException("set_status requires stepId")
                    val newStatus = op.payload?.get("status")
                        ?: throw IllegalArgumentException("set_status requires payload.status")
                    val note = op.payload?.get("note")

                    val childCount = dao.getChildCount(stepId)
                    if (childCount > 0) {
                        val node = dao.getById(stepId) ?: continue
                        throw ParentStatusDerivedException(
                            stepId = stepId,
                            title = node.title,
                            childCount = childCount
                        )
                    }

                    if (newStatus == "doing") {
                        dao.resetDoingToTodo(sessionId, now)
                    }

                    dao.updateStatus(stepId, newStatus, note, now)
                }
                "add_step" -> {
                    val parentId = op.parentId
                    val title = op.payload?.get("title") ?: "New step"
                    val sortOrderStr = op.payload?.get("sortOrder")
                    val sortOrder = sortOrderStr?.toIntOrNull() ?: 0
                    val id = op.stepId ?: java.util.UUID.randomUUID().toString()
                    val entity = TaskNodeEntity(
                        id = id,
                        sessionId = sessionId,
                        parentId = parentId,
                        sortOrder = sortOrder,
                        title = title,
                        status = "todo",
                        createdAt = now,
                        updatedAt = now
                    )
                    dao.upsert(entity)
                }
                "remove_step" -> {
                    val stepId = op.stepId
                        ?: throw IllegalArgumentException("remove_step requires stepId")
                    dropRecursive(stepId, now)
                }
                "move_step" -> {
                    val stepId = op.stepId
                        ?: throw IllegalArgumentException("move_step requires stepId")
                    val node = dao.getById(stepId) ?: continue
                    val newParentId = op.payload?.get("newParentId") ?: op.parentId
                    val newSortOrder = op.payload?.get("newSortOrder")?.toIntOrNull() ?: node.sortOrder
                    val updated = node.copy(
                        parentId = newParentId,
                        sortOrder = newSortOrder,
                        updatedAt = now
                    )
                    dao.upsert(updated)
                }
                "update_title" -> {
                    val stepId = op.stepId
                        ?: throw IllegalArgumentException("update_title requires stepId")
                    val newTitle = op.payload?.get("title")
                        ?: throw IllegalArgumentException("update_title requires payload.title")
                    val node = dao.getById(stepId) ?: continue
                    dao.upsert(node.copy(title = newTitle, updatedAt = now))
                }
                "set_note" -> {
                    val stepId = op.stepId
                        ?: throw IllegalArgumentException("set_note requires stepId")
                    val note = op.payload?.get("note")
                        ?: throw IllegalArgumentException("set_note requires payload.note")
                    val node = dao.getById(stepId) ?: continue
                    dao.upsert(node.copy(note = note, updatedAt = now))
                }
            }
        }

        return getPlan(sessionId)!!
    }

    override suspend fun getPlan(sessionId: String): TaskState? {
        val nodes = dao.getAllActiveBySession(sessionId)
        if (nodes.isEmpty()) return null

        val tree = buildTree(nodes)
        val (done, total) = countLeafProgress(tree)
        val rootStep = tree.firstOrNull()
        val doingNode = nodes.find { it.status == "doing" }

        return TaskState(
            id = rootStep?.id ?: "",
            title = rootStep?.title ?: "",
            status = deriveRootStatus(tree),
            steps = tree,
            currentFocusStepId = doingNode?.id,
            createdAt = rootStep?.createdAt ?: 0
        )
    }

    override suspend fun dropPlan(sessionId: String, reason: String) {
        val now = System.currentTimeMillis()
        val allNodes = dao.getAllActiveBySession(sessionId)
        for (node in allNodes) {
            dao.markDropped(node.id, now)
        }
    }

    override fun deriveParentStatus(children: List<TaskStep>): String {
        if (children.isEmpty()) return "todo"
        val statuses = children.map { it.status }
        return when {
            statuses.all { it == "done" } -> "done"
            statuses.any { it == "doing" } -> "doing"
            statuses.any { it == "dropped" } -> "partial_dropped"
            statuses.all { it == "todo" || it == "pending" } -> "todo"
            else -> "todo"
        }
    }

    override fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int> {
        var done = 0
        var total = 0
        fun walk(nodes: List<TaskStep>) {
            for (step in nodes) {
                if (step.children.isEmpty()) {
                    total++
                    if (step.status == "done") done++
                } else {
                    walk(step.children)
                }
            }
        }
        walk(steps)
        return done to total
    }

    private fun flattenTree(
        tree: List<TaskStep>,
        sessionId: String,
        parentId: String?,
        now: Long,
        out: MutableList<TaskNodeEntity>
    ) {
        for ((index, step) in tree.withIndex()) {
            val entity = TaskNodeEntity(
                id = step.id.ifBlank { java.util.UUID.randomUUID().toString() },
                sessionId = sessionId,
                parentId = parentId,
                sortOrder = if (step.sortOrder > 0) step.sortOrder else index,
                title = step.title,
                description = step.description,
                status = "todo",
                note = step.note,
                artifactFileUuids = step.artifactFileUuids?.let { uuids: List<String> ->
                    kotlinx.serialization.json.Json.encodeToString(uuids)
                },
                isCollapsed = step.isCollapsed,
                createdAt = if (step.createdAt > 0) step.createdAt else now,
                updatedAt = if (step.updatedAt > 0) step.updatedAt else now
            )
            out.add(entity)
            if (step.children.isNotEmpty()) {
                flattenTree(step.children, sessionId, entity.id, now, out)
            }
        }
    }

    private fun buildTree(entities: List<TaskNodeEntity>): List<TaskStep> {
        val byParent = entities.groupBy { it.parentId }
        fun toStep(entity: TaskNodeEntity): TaskStep {
            val childEntities = byParent[entity.id] ?: emptyList()
            val children = childEntities
                .sortedBy { it.sortOrder }
                .map { toStep(it) }
            val derivedStatus = if (children.isNotEmpty()) {
                deriveParentStatus(children)
            } else {
                entity.status
            }
            return TaskStep(
                id = entity.id,
                parentId = entity.parentId,
                title = entity.title,
                description = entity.description,
                status = derivedStatus,
                sortOrder = entity.sortOrder,
                note = entity.note,
                artifactFileUuids = entity.artifactFileUuids?.let { raw ->
                    try {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw)
                    } catch (_: Exception) {
                        null
                    }
                },
                children = children,
                isCollapsed = entity.isCollapsed,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
        return (byParent[null] ?: emptyList())
            .sortedBy { it.sortOrder }
            .map { toStep(it) }
    }

    private suspend fun dropRecursive(nodeId: String, now: Long) {
        val node = dao.getById(nodeId) ?: return
        if (node.status == "dropped") return
        val allNodes = dao.getAllActiveBySession(node.sessionId)
        val toDrop = collectDescendants(nodeId, allNodes)
        for (id in toDrop) {
            dao.markDropped(id, now)
        }
    }

    private fun collectDescendants(rootId: String, allNodes: List<TaskNodeEntity>): List<String> {
        val result = mutableListOf(rootId)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in allNodes.filter { it.parentId == current }) {
                result.add(child.id)
                queue.add(child.id)
            }
        }
        return result
    }

    private fun deriveRootStatus(steps: List<TaskStep>): String {
        if (steps.isEmpty()) return "idle"
        val (done, total) = countLeafProgress(steps)
        return when {
            total == 0 -> "idle"
            done == total -> "done"
            steps.any { hasDoing(it) } -> "active"
            steps.all { allDropped(it) } -> "dropped"
            else -> "active"
        }
    }

    private fun hasDoing(step: TaskStep): Boolean {
        if (step.status == "doing") return true
        return step.children.any { hasDoing(it) }
    }

    private fun allDropped(step: TaskStep): Boolean {
        if (step.children.isEmpty()) return step.status == "dropped"
        return step.children.all { allDropped(it) }
    }

    class ParentStatusDerivedException(
        val stepId: String,
        val title: String,
        val childCount: Int
    ) : Exception("步骤 '$title' 是父节点（含 $childCount 个子步骤），其状态由子节点自动派生，不可直接设置。")
}
