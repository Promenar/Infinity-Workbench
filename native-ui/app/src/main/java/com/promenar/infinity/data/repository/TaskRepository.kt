package com.promenar.infinity.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.dao.TaskNodeDao
import com.promenar.infinity.data.local.db.entity.TaskNodeEntity
import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.domain.repository.PlanPatchOp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/**
 * 会话任务计划仓库。
 *
 * 步骤 ID 由模型给出（常见为 "1"、"2"），而 `task_nodes.id` 是全局主键，因此落库 ID 统一加
 * `sessionId::` 命名空间，对外（模型、界面）始终使用去掉前缀的步骤 ID；所有读写都先在本会话的
 * 活跃节点中解析步骤，不同会话的同名步骤互不影响。无前缀的旧数据按原 ID 在本会话内兼容读取。
 *
 * 计划目标保存在一条隐藏的元数据节点中（状态 [META_STATUS]），不出现在步骤树、进度与上下文里。
 */
class TaskRepository(
    private val dao: TaskNodeDao,
    private val database: RoomDatabase? = null
) : ITaskRepository {

    override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> {
        return dao.observeActiveTree(sessionId).map { entities ->
            buildTree(sessionId, entities.filterNot(::isMeta))
        }
    }

    override fun observeGoal(sessionId: String): Flow<String?> =
        dao.observeActiveTree(sessionId).map { entities ->
            entities.takeIf { nodes -> nodes.any { !isMeta(it) } }?.firstOrNull(::isMeta)?.title
        }

    override suspend fun initializePlan(sessionId: String, goal: String, tree: List<TaskStep>): TaskState {
        validateTree(tree)
        if (database != null) {
            return database.withTransaction {
                initializePlanInternal(sessionId, goal, tree)
            }
        }
        return initializePlanInternal(sessionId, goal, tree)
    }

    private suspend fun initializePlanInternal(
        sessionId: String,
        goal: String,
        tree: List<TaskStep>,
    ): TaskState {
        val existing = dao.getAllActiveBySession(sessionId)
        val existingSteps = existing.filterNot(::isMeta)
        if (existingSteps.isNotEmpty()) {
            val existingTree = buildTree(sessionId, existingSteps)
            val (done, total) = countLeafProgress(existingTree)
            if (total > 0 && done == total) {
                dao.deleteBySession(sessionId)
                return createPlan(sessionId, goal, tree)
            }
            val rootStep = existingTree.firstOrNull()
            return TaskState(
                id = rootStep?.id ?: "",
                title = existing.firstOrNull(::isMeta)?.title ?: rootStep?.title ?: goal,
                status = "conflict",
                steps = existingTree,
                currentFocusStepId = existingSteps.find { it.status == "doing" }?.id?.let { externalId(sessionId, it) },
                createdAt = rootStep?.createdAt ?: System.currentTimeMillis()
            )
        }
        if (existing.isNotEmpty()) dao.deleteBySession(sessionId)
        return createPlan(sessionId, goal, tree)
    }

    private suspend fun createPlan(
        sessionId: String,
        goal: String,
        tree: List<TaskStep>,
    ): TaskState {
        val now = System.currentTimeMillis()
        val entities = mutableListOf<TaskNodeEntity>()
        flattenTree(tree, sessionId, parentId = null, now, entities)
        val firstLeaf = entities.firstOrNull { e -> entities.none { it.parentId == e.id } }
        dao.upsertAll(
            entities.map { if (it.id == firstLeaf?.id) it.copy(status = "doing") else it } +
                goalNode(sessionId, goal, now),
        )
        return requireNotNull(getPlan(sessionId)) { "计划创建后读取失败" }.copy(createdAt = now)
    }

    override suspend fun updatePlan(sessionId: String, operations: List<PlanPatchOp>): TaskState {
        if (database != null) {
            return database.withTransaction {
                applyPlanOperations(sessionId, operations)
                requireNotNull(getPlan(sessionId)) { NO_ACTIVE_PLAN_MESSAGE }
            }
        }

        applyPlanOperations(sessionId, operations)
        return requireNotNull(getPlan(sessionId)) { NO_ACTIVE_PLAN_MESSAGE }
    }

    private suspend fun applyPlanOperations(sessionId: String, operations: List<PlanPatchOp>) {
        val now = System.currentTimeMillis()

        for (op in operations) {
            val nodes = activeSteps(sessionId)
            check(nodes.isNotEmpty()) { NO_ACTIVE_PLAN_MESSAGE }
            when (op.action) {
                "set_status" -> {
                    val node = resolve(sessionId, nodes, op.stepId, op.action)
                    val newStatus = normalizeStatus(
                        op.payload?.get("status")
                            ?: throw IllegalArgumentException("set_status 需要 payload.status。"),
                    )
                    val childCount = nodes.count { it.parentId == node.id }
                    if (childCount > 0) {
                        throw ParentStatusDerivedException(
                            stepId = externalId(sessionId, node.id),
                            title = node.title,
                            childCount = childCount
                        )
                    }
                    if (newStatus == "doing") {
                        dao.resetDoingToTodo(sessionId, now)
                    }
                    dao.updateStatus(node.id, newStatus, op.payload?.get("note") ?: node.note, now)
                }
                "add_step" -> {
                    val parent = op.parentId?.let { resolve(sessionId, nodes, it, "add_step.parentId") }
                    val title = op.payload?.get("title")?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException("add_step 需要 payload.title。")
                    val requestedId = op.stepId?.takeIf(String::isNotBlank)
                    if (requestedId != null) {
                        require(requestedId != GOAL_NODE_KEY) { "步骤 ID \"$GOAL_NODE_KEY\" 为保留字。" }
                        require(findNode(sessionId, nodes, requestedId) == null) { "步骤 ID \"$requestedId\" 已存在。" }
                    }
                    val siblings = nodes.filter { it.parentId == parent?.id }
                    val sortOrder = op.payload?.get("sortOrder")?.toIntOrNull()
                        ?: ((siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                    dao.upsert(
                        TaskNodeEntity(
                            id = internalId(sessionId, requestedId ?: java.util.UUID.randomUUID().toString()),
                            sessionId = sessionId,
                            parentId = parent?.id,
                            sortOrder = sortOrder,
                            title = title,
                            status = "todo",
                            createdAt = now,
                            updatedAt = now
                        ),
                    )
                }
                "remove_step" -> {
                    val node = resolve(sessionId, nodes, op.stepId, op.action)
                    collectDescendants(node.id, nodes).forEach { dao.markDropped(it, now) }
                }
                "move_step" -> {
                    val node = resolve(sessionId, nodes, op.stepId, op.action)
                    val newParent = (op.payload?.get("newParentId") ?: op.parentId)
                        ?.let { resolve(sessionId, nodes, it, "move_step.newParentId") }
                    if (newParent != null) {
                        if (newParent.id == node.id || newParent.id in collectDescendants(node.id, nodes)) {
                            throw TaskCycleException(
                                externalId(sessionId, node.id),
                                externalId(sessionId, newParent.id),
                            )
                        }
                    }
                    val newSortOrder = op.payload?.get("newSortOrder")?.toIntOrNull() ?: node.sortOrder
                    dao.upsert(node.copy(parentId = newParent?.id, sortOrder = newSortOrder, updatedAt = now))
                }
                "update_title" -> {
                    val node = resolve(sessionId, nodes, op.stepId, op.action)
                    val newTitle = op.payload?.get("title")?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException("update_title 需要 payload.title。")
                    dao.upsert(node.copy(title = newTitle, updatedAt = now))
                }
                "set_note" -> {
                    val node = resolve(sessionId, nodes, op.stepId, op.action)
                    val note = op.payload?.get("note")
                        ?: throw IllegalArgumentException("set_note 需要 payload.note。")
                    dao.upsert(node.copy(note = note, updatedAt = now))
                }
                else -> throw IllegalArgumentException(
                    "未知操作 \"${op.action}\"，可用操作：set_status、add_step、remove_step、move_step、update_title、set_note。",
                )
            }
        }
    }

    override suspend fun getPlan(sessionId: String): TaskState? {
        val nodes = dao.getAllActiveBySession(sessionId)
        val steps = nodes.filterNot(::isMeta)
        if (steps.isEmpty()) return null

        val tree = buildTree(sessionId, steps)
        val rootStep = tree.firstOrNull()
        val doingNode = steps.find { it.status == "doing" }

        return TaskState(
            id = rootStep?.id ?: "",
            title = nodes.firstOrNull(::isMeta)?.title ?: rootStep?.title ?: "",
            status = deriveRootStatus(tree),
            steps = tree,
            currentFocusStepId = doingNode?.id?.let { externalId(sessionId, it) },
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

    private suspend fun activeSteps(sessionId: String): List<TaskNodeEntity> =
        dao.getAllActiveBySession(sessionId).filterNot(::isMeta)

    private fun findNode(sessionId: String, nodes: List<TaskNodeEntity>, stepId: String): TaskNodeEntity? =
        nodes.firstOrNull { it.id == internalId(sessionId, stepId) }
            ?: nodes.firstOrNull { it.id == stepId && it.sessionId == sessionId }

    private fun resolve(
        sessionId: String,
        nodes: List<TaskNodeEntity>,
        stepId: String?,
        action: String,
    ): TaskNodeEntity {
        val id = stepId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$action 需要 stepId。")
        return findNode(sessionId, nodes, id) ?: throw IllegalArgumentException(
            "步骤 \"$id\" 不存在。当前步骤：" +
                nodes.sortedBy { it.sortOrder }.take(MAX_LISTED_STEP_IDS)
                    .joinToString("、") { externalId(sessionId, it.id) } + "。",
        )
    }

    private fun validateTree(tree: List<TaskStep>) {
        val ids = mutableListOf<String>()
        fun walk(steps: List<TaskStep>) {
            steps.forEach { step ->
                require(step.title.isNotBlank()) { "步骤标题不能为空。" }
                step.id.takeIf(String::isNotBlank)?.let(ids::add)
                walk(step.children)
            }
        }
        walk(tree)
        require(tree.isNotEmpty()) { "tree 至少需要一个步骤。" }
        require(GOAL_NODE_KEY !in ids) { "步骤 ID \"$GOAL_NODE_KEY\" 为保留字。" }
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "步骤 ID 重复：${duplicates.joinToString("、")}。" }
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
                id = internalId(sessionId, step.id.ifBlank { java.util.UUID.randomUUID().toString() }),
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

    private fun buildTree(sessionId: String, entities: List<TaskNodeEntity>): List<TaskStep> {
        val byParent = entities.groupBy { it.parentId }
        fun toStep(entity: TaskNodeEntity): TaskStep {
            val children = (byParent[entity.id] ?: emptyList())
                .sortedBy { it.sortOrder }
                .map { toStep(it) }
            return TaskStep(
                id = externalId(sessionId, entity.id),
                parentId = entity.parentId?.let { externalId(sessionId, it) },
                title = entity.title,
                description = entity.description,
                status = if (children.isNotEmpty()) deriveParentStatus(children) else entity.status,
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
        val ids = entities.mapTo(hashSetOf()) { it.id }
        return entities
            .filter { it.parentId == null || it.parentId !in ids }
            .sortedBy { it.sortOrder }
            .map { toStep(it) }
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
            else -> "active"
        }
    }

    private fun goalNode(sessionId: String, goal: String, now: Long) = TaskNodeEntity(
        id = internalId(sessionId, GOAL_NODE_KEY),
        sessionId = sessionId,
        sortOrder = -1,
        title = goal,
        status = META_STATUS,
        createdAt = now,
        updatedAt = now,
    )

    private fun isMeta(entity: TaskNodeEntity): Boolean = entity.status == META_STATUS

    class ParentStatusDerivedException(
        val stepId: String,
        val title: String,
        val childCount: Int
    ) : Exception("步骤 '$title' 是父节点（含 $childCount 个子步骤），其状态由子节点自动派生，不可直接设置。")

    class TaskCycleException(
        val stepId: String,
        val newParentId: String
    ) : Exception("不能把任务步骤移动到自身或自己的子步骤下。")

    companion object {
        /** 计划目标元数据节点的状态值；不属于任何步骤状态。 */
        const val META_STATUS = "meta"
        const val GOAL_NODE_KEY = "__goal__"
        const val NO_ACTIVE_PLAN_MESSAGE = "当前没有活跃计划，请先调用 initialize_plan 创建计划。"
        private const val ID_SEPARATOR = "::"
        private const val MAX_LISTED_STEP_IDS = 30

        /** 步骤状态的规范取值。 */
        val STEP_STATUSES = listOf("todo", "doing", "done", "dropped")

        /** 把模型常用的状态同义词归一为规范取值；无法识别时抛出带可用取值的错误。 */
        fun normalizeStatus(raw: String): String = when (raw.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "todo", "pending", "not_started", "open" -> "todo"
            "doing", "in_progress", "active", "started", "working" -> "doing"
            "done", "completed", "complete", "finished", "success", "succeeded" -> "done"
            "dropped", "skipped", "cancelled", "canceled", "abandoned" -> "dropped"
            else -> throw IllegalArgumentException(
                "无效状态 \"$raw\"，可用值：${STEP_STATUSES.joinToString("、")}。",
            )
        }

        internal fun internalId(sessionId: String, externalId: String): String =
            "$sessionId$ID_SEPARATOR$externalId"

        internal fun externalId(sessionId: String, internalId: String): String =
            internalId.removePrefix("$sessionId$ID_SEPARATOR")
    }
}
