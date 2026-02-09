/**
 * SessionRepository - 会话和消息的 SQLite 数据访问层
 * Phase 4b: SQLite Session 迁移
 */

import { db } from './index';
import type { Session, Message } from '../../types/chat';

// ==================== Session CRUD ====================

/**
 * 创建新会话
 */
export async function createSession(session: Session): Promise<void> {
    const now = Date.now();
    await db.execute(
        `INSERT INTO sessions (
      id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
      is_pinned, scroll_offset, draft, execution_mode, loop_status,
      pending_intervention, approval_request, rag_options, inference_params,
      active_task, stats, options, active_mcp_server_ids, active_skill_ids, created_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
            session.id,
            session.agentId,
            session.title,
            session.lastMessage || null,
            session.time || null,
            session.unread || 0,
            session.modelId || null,
            session.customPrompt || null,
            session.isPinned ? 1 : 0,
            session.scrollOffset || null,
            session.draft || null,
            session.executionMode || 'auto',
            session.loopStatus || 'idle',
            session.pendingIntervention || null,
            session.approvalRequest ? JSON.stringify(session.approvalRequest) : null,
            session.ragOptions ? JSON.stringify(session.ragOptions) : null,
            session.inferenceParams ? JSON.stringify(session.inferenceParams) : null,
            session.activeTask ? JSON.stringify(session.activeTask) : null,
            session.stats ? JSON.stringify(session.stats) : null,
            session.options ? JSON.stringify(session.options) : null,
            session.activeMcpServerIds ? JSON.stringify(session.activeMcpServerIds) : null,
            session.activeSkillIds ? JSON.stringify(session.activeSkillIds) : null,
            now,
            now,
        ]
    );
}

/**
 * 获取单个会话（不含消息）
 */
export async function getSessionById(id: string): Promise<Session | null> {
    const result = await db.execute('SELECT * FROM sessions WHERE id = ?', [id]);
    const rows = (result.rows as any)._array || (result.rows as any) || [];
    if (rows.length === 0) return null;
    return rowToSession(rows[0]);
}

/**
 * 获取所有会话（不含消息）
 */
export async function getAllSessions(): Promise<Session[]> {
    const result = await db.execute('SELECT * FROM sessions ORDER BY updated_at DESC');
    const rows = (result.rows as any)._array || (result.rows as any) || [];
    return rows.map(rowToSession);
}

/**
 * 更新会话
 */
export async function updateSession(id: string, updates: Partial<Session>): Promise<void> {
    const setClauses: string[] = [];
    const values: any[] = [];

    if (updates.title !== undefined) { setClauses.push('title = ?'); values.push(updates.title); }
    if (updates.lastMessage !== undefined) { setClauses.push('last_message = ?'); values.push(updates.lastMessage); }
    if (updates.time !== undefined) { setClauses.push('time = ?'); values.push(updates.time); }
    if (updates.unread !== undefined) { setClauses.push('unread = ?'); values.push(updates.unread); }
    if (updates.modelId !== undefined) { setClauses.push('model_id = ?'); values.push(updates.modelId); }
    if (updates.customPrompt !== undefined) { setClauses.push('custom_prompt = ?'); values.push(updates.customPrompt); }
    if (updates.isPinned !== undefined) { setClauses.push('is_pinned = ?'); values.push(updates.isPinned ? 1 : 0); }
    if (updates.scrollOffset !== undefined) { setClauses.push('scroll_offset = ?'); values.push(updates.scrollOffset); }
    if (updates.draft !== undefined) { setClauses.push('draft = ?'); values.push(updates.draft); }
    if (updates.executionMode !== undefined) { setClauses.push('execution_mode = ?'); values.push(updates.executionMode); }
    if (updates.loopStatus !== undefined) { setClauses.push('loop_status = ?'); values.push(updates.loopStatus); }
    if (updates.pendingIntervention !== undefined) { setClauses.push('pending_intervention = ?'); values.push(updates.pendingIntervention); }
    if (updates.approvalRequest !== undefined) { setClauses.push('approval_request = ?'); values.push(updates.approvalRequest ? JSON.stringify(updates.approvalRequest) : null); }
    if (updates.ragOptions !== undefined) { setClauses.push('rag_options = ?'); values.push(updates.ragOptions ? JSON.stringify(updates.ragOptions) : null); }
    if (updates.inferenceParams !== undefined) { setClauses.push('inference_params = ?'); values.push(updates.inferenceParams ? JSON.stringify(updates.inferenceParams) : null); }
    if (updates.activeTask !== undefined) { setClauses.push('active_task = ?'); values.push(updates.activeTask ? JSON.stringify(updates.activeTask) : null); }
    if (updates.stats !== undefined) { setClauses.push('stats = ?'); values.push(updates.stats ? JSON.stringify(updates.stats) : null); }
    if (updates.options !== undefined) { setClauses.push('options = ?'); values.push(updates.options ? JSON.stringify(updates.options) : null); }
    if (updates.activeMcpServerIds !== undefined) { setClauses.push('active_mcp_server_ids = ?'); values.push(updates.activeMcpServerIds ? JSON.stringify(updates.activeMcpServerIds) : null); }
    if (updates.activeSkillIds !== undefined) { setClauses.push('active_skill_ids = ?'); values.push(updates.activeSkillIds ? JSON.stringify(updates.activeSkillIds) : null); }

    if (setClauses.length === 0) return;

    setClauses.push('updated_at = ?');
    values.push(Date.now());
    values.push(id);

    const sql = `UPDATE sessions SET ${setClauses.join(', ')} WHERE id = ?`;

    try {
        await db.execute(sql, values);
    } catch (e: any) {
        // 🛡️ Self-healing: 自动修复缺失字段 (Schema Drift Auto-Fix)
        const errorStr = String(e);
        if (errorStr.includes('no such column')) {
            console.warn('[SessionRepository] Schema drift detected, attempting self-repair...', errorStr);

            try {
                // 1. 获取当前表结构
                const result = await db.execute('PRAGMA table_info(sessions)');
                const columns = ((result.rows as any)._array || (result.rows as any) || [])
                    .map((row: any) => row.name);

                // 2. 补全可能缺失的列
                const missingCols: string[] = [];
                if (updates.options !== undefined && !columns.includes('options')) missingCols.push('options');
                if (updates.ragOptions !== undefined && !columns.includes('rag_options')) missingCols.push('rag_options');
                if (updates.activeMcpServerIds !== undefined && !columns.includes('active_mcp_server_ids')) missingCols.push('active_mcp_server_ids');
                if (updates.activeSkillIds !== undefined && !columns.includes('active_skill_ids')) missingCols.push('active_skill_ids');
                if (updates.stats !== undefined && !columns.includes('stats')) missingCols.push('stats');
                if (updates.activeTask !== undefined && !columns.includes('active_task')) missingCols.push('active_task');

                for (const col of missingCols) {
                    console.log(`[SessionRepository] Auto-creating missing column: ${col}`);
                    await db.execute(`ALTER TABLE sessions ADD COLUMN ${col} TEXT`);
                }

                // 3. 重试更新
                if (missingCols.length > 0) {
                    await db.execute(sql, values);
                    console.log('[SessionRepository] Self-repair successful, update retried.');
                    return;
                }
            } catch (repairError) {
                console.error('[SessionRepository] Self-repair failed:', repairError);
            }
        }
        // 如果修复失败或非 Schema 错误，重新抛出
        throw e;
    }
}

/**
 * 删除会话（消息会通过 CASCADE 自动删除）
 */
export async function deleteSession(id: string): Promise<void> {
    await db.execute('DELETE FROM sessions WHERE id = ?', [id]);
}

// ==================== Message CRUD ====================

/**
 * 添加消息
 */
export async function addMessage(sessionId: string, message: Message): Promise<void> {
    await db.execute(
        `INSERT INTO messages (
      id, session_id, role, content, model_id, status, reasoning, thought_signature,
      images, tokens, citations, rag_references, rag_progress, rag_metadata,
      rag_references_loading, execution_steps, tool_calls, pending_approval_tool_ids,
      tool_call_id, name, planning_task, is_archived, vectorization_status,
      layout_height, tool_results, created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
            message.id,
            sessionId,
            message.role,
            message.content,
            message.modelId || null,
            message.status || null,
            message.reasoning || null,
            message.thought_signature || null,
            message.images ? JSON.stringify(message.images) : null,
            message.tokens ? JSON.stringify(message.tokens) : null,
            message.citations ? JSON.stringify(message.citations) : null,
            message.ragReferences ? JSON.stringify(message.ragReferences) : null,
            message.ragProgress ? JSON.stringify(message.ragProgress) : null,
            message.ragMetadata ? JSON.stringify(message.ragMetadata) : null,
            message.ragReferencesLoading ? 1 : 0,
            message.executionSteps ? JSON.stringify(message.executionSteps) : null,
            message.tool_calls ? JSON.stringify(message.tool_calls) : null,
            message.pendingApprovalToolIds ? JSON.stringify(message.pendingApprovalToolIds) : null,
            message.tool_call_id || null,
            message.name || null,
            message.planningTask ? JSON.stringify(message.planningTask) : null,
            message.isArchived ? 1 : 0,
            message.vectorizationStatus || null,
            message.layoutHeight || null,
            message.toolResults ? JSON.stringify(message.toolResults) : null,
            message.createdAt,
        ]
    );

    // 同时更新 session 的 updated_at
    await db.execute('UPDATE sessions SET updated_at = ? WHERE id = ?', [Date.now(), sessionId]);
}

/**
 * 更新消息
 */
export async function updateMessage(sessionId: string, messageId: string, updates: Partial<Message>): Promise<void> {
    const setClauses: string[] = [];
    const values: any[] = [];

    if (updates.content !== undefined) { setClauses.push('content = ?'); values.push(updates.content); }
    if (updates.status !== undefined) { setClauses.push('status = ?'); values.push(updates.status); }
    if (updates.reasoning !== undefined) { setClauses.push('reasoning = ?'); values.push(updates.reasoning); }
    if (updates.thought_signature !== undefined) { setClauses.push('thought_signature = ?'); values.push(updates.thought_signature); }
    if (updates.images !== undefined) { setClauses.push('images = ?'); values.push(updates.images ? JSON.stringify(updates.images) : null); }
    if (updates.tokens !== undefined) { setClauses.push('tokens = ?'); values.push(updates.tokens ? JSON.stringify(updates.tokens) : null); }
    if (updates.citations !== undefined) { setClauses.push('citations = ?'); values.push(updates.citations ? JSON.stringify(updates.citations) : null); }
    if (updates.ragReferences !== undefined) { setClauses.push('rag_references = ?'); values.push(updates.ragReferences ? JSON.stringify(updates.ragReferences) : null); }
    if (updates.ragProgress !== undefined) { setClauses.push('rag_progress = ?'); values.push(updates.ragProgress ? JSON.stringify(updates.ragProgress) : null); }
    if (updates.ragMetadata !== undefined) { setClauses.push('rag_metadata = ?'); values.push(updates.ragMetadata ? JSON.stringify(updates.ragMetadata) : null); }
    if (updates.ragReferencesLoading !== undefined) { setClauses.push('rag_references_loading = ?'); values.push(updates.ragReferencesLoading ? 1 : 0); }
    if (updates.executionSteps !== undefined) { setClauses.push('execution_steps = ?'); values.push(updates.executionSteps ? JSON.stringify(updates.executionSteps) : null); }
    if (updates.tool_calls !== undefined) { setClauses.push('tool_calls = ?'); values.push(updates.tool_calls ? JSON.stringify(updates.tool_calls) : null); }
    if (updates.pendingApprovalToolIds !== undefined) { setClauses.push('pending_approval_tool_ids = ?'); values.push(updates.pendingApprovalToolIds ? JSON.stringify(updates.pendingApprovalToolIds) : null); }
    if (updates.planningTask !== undefined) { setClauses.push('planning_task = ?'); values.push(updates.planningTask ? JSON.stringify(updates.planningTask) : null); }
    if (updates.isArchived !== undefined) { setClauses.push('is_archived = ?'); values.push(updates.isArchived ? 1 : 0); }
    if (updates.vectorizationStatus !== undefined) { setClauses.push('vectorization_status = ?'); values.push(updates.vectorizationStatus); }
    if (updates.layoutHeight !== undefined) { setClauses.push('layout_height = ?'); values.push(updates.layoutHeight); }
    if (updates.toolResults !== undefined) { setClauses.push('tool_results = ?'); values.push(updates.toolResults ? JSON.stringify(updates.toolResults) : null); }

    if (setClauses.length === 0) return;

    values.push(messageId);
    await db.execute(`UPDATE messages SET ${setClauses.join(', ')} WHERE id = ?`, values);

    // 同时更新 session 的 updated_at
    await db.execute('UPDATE sessions SET updated_at = ? WHERE id = ?', [Date.now(), sessionId]);
}

/**
 * 删除消息
 */
export async function deleteMessage(sessionId: string, messageId: string): Promise<void> {
    await db.execute('DELETE FROM messages WHERE id = ?', [messageId]);
    await db.execute('UPDATE sessions SET updated_at = ? WHERE id = ?', [Date.now(), sessionId]);
}

/**
 * 删除指定时间之后的所有消息 (用于重新发送时的截断)
 */
export async function deleteMessagesAfter(sessionId: string, timestamp: number): Promise<void> {
    await db.execute(
        'DELETE FROM messages WHERE session_id = ? AND created_at >= ?',
        [sessionId, timestamp]
    );
    await db.execute('UPDATE sessions SET updated_at = ? WHERE id = ?', [Date.now(), sessionId]);
}


/**
 * 获取会话的所有消息 (Standard)
 */
export async function getMessages(sessionId: string, limit?: number, offset?: number): Promise<Message[]> {
    let sql = 'SELECT * FROM messages WHERE session_id = ? ORDER BY created_at ASC';
    const params: any[] = [sessionId];

    if (limit !== undefined) {
        sql += ' LIMIT ?';
        params.push(limit);
        if (offset !== undefined) {
            sql += ' OFFSET ?';
            params.push(offset);
        }
    }

    const result = await db.execute(sql, params);
    const rows = (result.rows as any)._array || (result.rows as any) || [];
    return rows.map(rowToMessage);
}

/**
 * 获取指定时间之前的消息 (Offset Pagination via Cursor)
 * 用于倒序/上拉加载更多
 */
export async function getMessagesBefore(sessionId: string, beforeTimestamp: number, limit: number = 20): Promise<Message[]> {
    const sql = 'SELECT * FROM messages WHERE session_id = ? AND created_at < ? ORDER BY created_at DESC LIMIT ?';
    const params = [sessionId, beforeTimestamp, limit];

    const result = await db.execute(sql, params);
    const rows = (result.rows as any)._array || (result.rows as any) || [];

    // 数据库查出来是 DESC (最新到最旧)，但前端通常需要 ASC (旧到新) 来渲染
    // 既然是 getMessagesBefore，我们需要的是“历史片段”。
    // 为了保持一致性，返回时按 ASC 排序。
    return rows.map(rowToMessage).reverse();
}

/**
 * 获取最新的 N 条消息
 * 用于进入会话时的初始加载
 */
export async function getLatestMessages(sessionId: string, limit: number = 20): Promise<Message[]> {
    // 先按 DESC 取最新的 N 条
    const sql = 'SELECT * FROM messages WHERE session_id = ? ORDER BY created_at DESC LIMIT ?';
    const params = [sessionId, limit];

    const result = await db.execute(sql, params);
    const rows = (result.rows as any)._array || (result.rows as any) || [];

    // 反转回 ASC 顺序
    return rows.map(rowToMessage).reverse();
}

/**
 * 获取完整会话（含消息）
 */
export async function getFullSession(id: string): Promise<Session | null> {
    const session = await getSessionById(id);
    if (!session) return null;

    const messages = await getMessages(id);
    return { ...session, messages };
}

/**
 * 获取所有完整会话（含消息）
 */
export async function getAllFullSessions(): Promise<Session[]> {
    const sessions = await getAllSessions();
    const result: Session[] = [];

    for (const session of sessions) {
        const messages = await getMessages(session.id);
        result.push({ ...session, messages });
    }

    return result;
}

// ==================== 辅助函数 ====================

function rowToSession(row: any): Session {
    return {
        id: row.id,
        agentId: row.agent_id,
        title: row.title,
        lastMessage: row.last_message || '',
        time: row.time || '',
        unread: row.unread || 0,
        messages: [], // 需要单独加载
        modelId: row.model_id || undefined,
        customPrompt: row.custom_prompt || undefined,
        isPinned: row.is_pinned === 1,
        scrollOffset: row.scroll_offset || undefined,
        draft: row.draft || undefined,
        executionMode: row.execution_mode || 'auto',
        loopStatus: row.loop_status || 'idle',
        pendingIntervention: row.pending_intervention || undefined,
        approvalRequest: row.approval_request ? JSON.parse(row.approval_request) : undefined,
        ragOptions: row.rag_options ? JSON.parse(row.rag_options) : undefined,
        inferenceParams: row.inference_params ? JSON.parse(row.inference_params) : undefined,
        activeTask: row.active_task ? JSON.parse(row.active_task) : undefined,
        stats: row.stats ? JSON.parse(row.stats) : undefined,
        options: row.options ? JSON.parse(row.options) : undefined,
        activeMcpServerIds: row.active_mcp_server_ids ? JSON.parse(row.active_mcp_server_ids) : undefined,
        activeSkillIds: row.active_skill_ids ? JSON.parse(row.active_skill_ids) : undefined,
    };
}

function rowToMessage(row: any): Message {
    return {
        id: row.id,
        role: row.role,
        content: row.content,
        createdAt: row.created_at,
        modelId: row.model_id || undefined,
        status: row.status || undefined,
        reasoning: row.reasoning || undefined,
        thought_signature: row.thought_signature || undefined,
        images: row.images ? JSON.parse(row.images) : undefined,
        tokens: row.tokens ? JSON.parse(row.tokens) : undefined,
        citations: row.citations ? JSON.parse(row.citations) : undefined,
        ragReferences: row.rag_references ? JSON.parse(row.rag_references) : undefined,
        ragProgress: row.rag_progress ? JSON.parse(row.rag_progress) : undefined,
        ragMetadata: row.rag_metadata ? JSON.parse(row.rag_metadata) : undefined,
        ragReferencesLoading: row.rag_references_loading === 1,
        executionSteps: row.execution_steps ? JSON.parse(row.execution_steps) : undefined,
        tool_calls: row.tool_calls ? JSON.parse(row.tool_calls) : undefined,
        pendingApprovalToolIds: row.pending_approval_tool_ids ? JSON.parse(row.pending_approval_tool_ids) : undefined,
        tool_call_id: row.tool_call_id || undefined,
        name: row.name || undefined,
        planningTask: row.planning_task ? JSON.parse(row.planning_task) : undefined,
        isArchived: row.is_archived === 1,
        vectorizationStatus: row.vectorization_status || undefined,
        layoutHeight: row.layout_height || undefined,
        toolResults: row.tool_results ? JSON.parse(row.tool_results) : undefined,
    };
}

// 🔑 导出所有函数作为 SessionRepository 命名空间
export const SessionRepository = {
    // Session
    create: createSession,
    getById: getSessionById,
    getAll: getAllSessions,
    update: updateSession,
    delete: deleteSession,
    // Message
    addMessage,
    updateMessage,
    deleteMessage,
    deleteMessagesAfter,
    getMessages,
    getMessagesBefore,
    getLatestMessages,

    // Full
    getFullSession,
    getAllFullSessions,
};
