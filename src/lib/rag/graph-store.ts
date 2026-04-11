import { db } from '../db';
import { generateId } from '../utils/id-generator';

export interface KGNode {
  id: string;
  name: string;
  type: string; // 'concept'|'person'|'org'|'location'...
  metadata?: Record<string, any>;
  sourceType?: 'full' | 'summary' | 'jit';
  createdAt: number;
}

export interface KGEdge {
  id: string;
  sourceId: string;
  targetId: string;
  relation: string;
  weight: number;
  docId?: string;
  sourceType?: 'full' | 'summary' | 'jit';
  createdAt: number;
}

export interface Tag {
  id: string;
  name: string;
  color: string;
  createdAt: number;
}

export class GraphStore {
  // ==========================================
  // Knowledge Graph Operations (Nodes & Edges)
  // ==========================================

  private mergeMetadata(existingMeta: string | null, newMeta: any): Record<string, any> {
    let result: Record<string, any> = {};

    try {
      result = existingMeta ? JSON.parse(existingMeta) : {};
    } catch (e) { }

    if (!newMeta) return result;

    for (const key of Object.keys(newMeta)) {
      const newVal = newMeta[key];
      const oldVal = result[key];

      if (Array.isArray(newVal) && Array.isArray(oldVal)) {
        const combined = [...oldVal, ...newVal];
        result[key] = combined.filter((item, index, self) =>
          index === self.findIndex((t) => JSON.stringify(t) === JSON.stringify(item))
        );
      } else if (newVal !== undefined && newVal !== null) {
        result[key] = newVal;
      }
    }

    return result;
  }

  private resolveType(existingType: string, newType: string): string {
    // 策略: 未知类型优先 (通常代表更精确的 LLM 推断，例如 Mathematician 优于 Person)
    const KNOWN_TYPES = new Set(['concept', 'person', 'org', 'location', 'event', 'product']);
    
    const existingIsKnown = KNOWN_TYPES.has(existingType.toLowerCase());
    const newIsKnown = KNOWN_TYPES.has(newType.toLowerCase());
    
    // 1. 已知 -> 未知: 升级为更精确的自定义类型
    if (existingIsKnown && !newIsKnown) return newType;
    
    // 2. 未知 -> 已知: 保留现有的更精确类型，避免降级
    if (!existingIsKnown && newIsKnown) return existingType;
    
    // 3. 均为已知类型: 遵循原有优先级
    if (existingIsKnown && newIsKnown) {
      const typePriority = ['concept', 'person', 'org', 'location', 'event', 'product'];
      const existingPriority = typePriority.indexOf(existingType.toLowerCase());
      const newPriority = typePriority.indexOf(newType.toLowerCase());
      return newPriority > existingPriority ? newType : existingType;
    }

    // 4. 均为未知类型: 以最新提取的为准 (最近更新优先)
    return newType;
  }

  /**
   * Upsert a node (create if not exists by name)
   * Uses INSERT OR IGNORE to handle race conditions
   */
  async upsertNode(
    name: string,
    type: string = 'concept',
    metadata: any = {},
    scope?: { sessionId?: string; agentId?: string },
    sourceType: 'full' | 'summary' | 'jit' = 'full',
  ): Promise<string> {
    try {
      const id = generateId();
      const createdAt = Date.now();

      const insertResult = await db.execute(
        `INSERT OR IGNORE INTO kg_nodes (id, name, type, metadata, created_at, session_id, agent_id, source_type) 
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        [id, name, type, JSON.stringify(metadata), createdAt, scope?.sessionId || null, scope?.agentId || null, sourceType]
      );

      if (insertResult.rowsAffected > 0) {
        return id;
      }

      const existing = await db.execute('SELECT * FROM kg_nodes WHERE name = ?', [name]);

      if (existing.rows && existing.rows.length > 0) {
        const row = (existing.rows as any)[0];
        const existingId = row.id;

        const mergedMetadata = this.mergeMetadata(row.metadata, metadata);
        const updatedType = this.resolveType(row.type, type);

        // 升级逻辑: jit -> full/summary 可升级，反之不可降级
        const sourcePriority = { 'full': 2, 'summary': 1, 'jit': 0 };
        const existingPriority = sourcePriority[row.source_type as keyof typeof sourcePriority] ?? 0;
        const newPriority = sourcePriority[sourceType] ?? 0;
        const finalSourceType = newPriority >= existingPriority ? sourceType : row.source_type;

        await db.execute(
          'UPDATE kg_nodes SET type = ?, metadata = ?, updated_at = ?, source_type = ? WHERE id = ?',
          [updatedType, JSON.stringify(mergedMetadata), Date.now(), finalSourceType, existingId]
        );

        return existingId;
      }

      throw new Error(`Failed to upsert node: ${name}`);
    } catch (e: any) {
      if (e.message && (e.message.includes('UNIQUE constraint failed') || e.message.includes('SQLITE_CONSTRAINT'))) {
        throw e;
      }
      console.error('Failed to upsert KG node:', e);
      throw e;
    }
  }

  /**
   * Update node properties
   */
  async updateNode(id: string, updates: { name?: string; type?: string }): Promise<void> {
    try {
      if (!updates.name && !updates.type) return;

      let query = 'UPDATE kg_nodes SET ';
      const params: any[] = [];
      const parts: string[] = [];

      if (updates.name) {
        parts.push('name = ?');
        params.push(updates.name);
      }
      if (updates.type) {
        parts.push('type = ?');
        params.push(updates.type);
      }

      query += parts.join(', ') + ' WHERE id = ?';
      params.push(id);

      await db.execute(query, params);
    } catch (e: any) {
      // Suppress logging for known handled errors
      if (e.message && (e.message.includes('UNIQUE constraint failed') || e.message.includes('SQLITE_CONSTRAINT'))) {
        throw e;
      }
      console.error('Failed to update KG node:', e);
      throw e;
    }
  }

  /**
   * Delete node and associated edges (Cascade handled by DB mostly, but explicit check good)
   */
  async deleteNode(id: string): Promise<void> {
    try {
      // Edges cascade on delete due to schema FOREIGN KEY
      await db.execute('DELETE FROM kg_nodes WHERE id = ?', [id]);
    } catch (e) {
      console.error('Failed to delete KG node:', e);
      throw e;
    }
  }

  /**
   * Merge source node into target node (by name).
   * Moves all edges from source to target, merges metadata, and deletes source.
   * Cleans up self-loops and duplicate edges after merge.
   */
  async mergeNodes(sourceId: string, targetName: string): Promise<void> {
    try {
      // 1. Get target node
      const targetRes = await db.execute('SELECT * FROM kg_nodes WHERE name = ?', [targetName]);
      if (!targetRes.rows || targetRes.rows.length === 0) {
        throw new Error(`Target node '${targetName}' not found`);
      }
      const targetNode = (targetRes.rows as any)[0];
      const targetId = targetNode.id;

      if (sourceId === targetId) return; // Same node

      console.log(`[GraphStore] Merging node ${sourceId} into ${targetId} (${targetName})`);

      // 2. Move Edges
      await db.execute(
        'UPDATE kg_edges SET source_id = ? WHERE source_id = ?',
        [targetId, sourceId]
      );
      await db.execute(
        'UPDATE kg_edges SET target_id = ? WHERE target_id = ?',
        [targetId, sourceId]
      );

      // 3. Clean up self-loop edges (source_id = target_id)
      const selfLoopResult = await db.execute(
        'DELETE FROM kg_edges WHERE source_id = ? AND target_id = ?',
        [targetId, targetId]
      );
      if (selfLoopResult.rowsAffected > 0) {
        console.log(`[GraphStore] Cleaned ${selfLoopResult.rowsAffected} self-loop edges`);
      }

      // 4. Clean up duplicate edges (keep the one with highest weight)
      await db.execute(`
        DELETE FROM kg_edges 
        WHERE id IN (
          SELECT id FROM kg_edges 
          WHERE source_id = ? OR target_id = ?
          EXCEPT
          SELECT id FROM (
            SELECT id, MAX(weight) as max_weight
            FROM kg_edges
            WHERE source_id = ? OR target_id = ?
            GROUP BY source_id, target_id, relation
          )
        )
      `, [targetId, targetId, targetId, targetId]);

      // 5. Merge Metadata
      const sourceRes = await db.execute('SELECT * FROM kg_nodes WHERE id = ?', [sourceId]);
      if (sourceRes.rows && sourceRes.rows.length > 0) {
        const sourceNode = (sourceRes.rows as any)[0];
        const mergedMeta = this.mergeMetadata(targetNode.metadata, sourceNode.metadata ? JSON.parse(sourceNode.metadata) : {});

        await db.execute(
          'UPDATE kg_nodes SET metadata = ?, updated_at = ? WHERE id = ?',
          [JSON.stringify(mergedMeta), Date.now(), targetId]
        );
      }

      // 6. Delete Source Node
      await db.execute('DELETE FROM kg_nodes WHERE id = ?', [sourceId]);

    } catch (e) {
      console.error('Failed to merge KG nodes:', e);
      throw e;
    }
  }

  /**
   * Create an edge between two nodes (with deduplication)
   */
  async createEdge(
    sourceId: string,
    targetId: string,
    relation: string,
    docId?: string,
    weight: number = 1.0,
    scope?: { sessionId?: string; agentId?: string },
    sourceType: 'full' | 'summary' | 'jit' = 'full',
  ): Promise<string> {
    try {
      const sessionId = scope?.sessionId || null;
      const agentId = scope?.agentId || null;

      const existing = await db.execute(
        `SELECT id, weight, source_type FROM kg_edges 
         WHERE source_id = ? AND target_id = ? AND relation = ?
         AND (doc_id = ? OR (doc_id IS NULL AND ? IS NULL))
         AND (session_id = ? OR (session_id IS NULL AND ? IS NULL))
         AND (agent_id = ? OR (agent_id IS NULL AND ? IS NULL))`,
        [sourceId, targetId, relation, docId || null, docId || null, sessionId, sessionId, agentId, agentId]
      );

      if (existing.rows && existing.rows.length > 0) {
        const existingEdge = (existing.rows as any)[0];
        const newWeight = (existingEdge.weight || 1) + weight;

        const sourcePriority = { 'full': 2, 'summary': 1, 'jit': 0 };
        const existingPriority = sourcePriority[existingEdge.source_type as keyof typeof sourcePriority] ?? 0;
        const newPriority = sourcePriority[sourceType] ?? 0;
        const finalSourceType = newPriority >= existingPriority ? sourceType : existingEdge.source_type;

        await db.execute(
          'UPDATE kg_edges SET weight = ?, created_at = ?, source_type = ? WHERE id = ?',
          [newWeight, Date.now(), finalSourceType, existingEdge.id]
        );

        return existingEdge.id;
      }

      const id = generateId();
      const createdAt = Date.now();
      await db.execute(
        'INSERT INTO kg_edges (id, source_id, target_id, relation, weight, doc_id, created_at, session_id, agent_id, source_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [id, sourceId, targetId, relation, weight, docId || null, createdAt, sessionId, agentId, sourceType],
      );
      return id;
    } catch (e) {
      console.error('[GraphStore] Failed to create edge:', e);
      throw e;
    }
  }

  /**
   * Update edge properties
   */
  async updateEdge(id: string, updates: { relation?: string; weight?: number }): Promise<void> {
    try {
      if (!updates.relation && updates.weight === undefined) return;

      let query = 'UPDATE kg_edges SET ';
      const params: any[] = [];
      const parts: string[] = [];

      if (updates.relation) {
        parts.push('relation = ?');
        params.push(updates.relation);
      }
      if (updates.weight !== undefined) {
        parts.push('weight = ?');
        params.push(updates.weight);
      }

      query += parts.join(', ') + ' WHERE id = ?';
      params.push(id);

      await db.execute(query, params);
    } catch (e) {
      console.error('Failed to update KG edge:', e);
      throw e;
    }
  }

  /**
   * Delete edge
   */
  async deleteEdge(id: string): Promise<void> {
    try {
      await db.execute('DELETE FROM kg_edges WHERE id = ?', [id]);
    } catch (e) {
      console.error('Failed to delete KG edge:', e);
      throw e;
    }
  }

  /**
   * Get all nodes
   */
  async getAllNodes(): Promise<KGNode[]> {
    const res = await db.execute('SELECT * FROM kg_nodes');
    if (!res.rows) return [];
    const nodes: KGNode[] = [];
    for (let i = 0; i < res.rows.length; i++) {
      const row = (res.rows as any)[i];
      nodes.push({
        id: row.id,
        name: row.name,
        type: row.type,
        metadata: row.metadata ? JSON.parse(row.metadata) : {},
        createdAt: row.created_at,
      });
    }
    return nodes;
  }

  /**
   * Get edges for a specific node (all incoming/outgoing)
   */
  async getEdgesForNode(nodeId: string): Promise<KGEdge[]> {
    const res = await db.execute('SELECT * FROM kg_edges WHERE source_id = ? OR target_id = ?', [
      nodeId,
      nodeId,
    ]);
    if (!res.rows) return [];
    const edges: KGEdge[] = [];
    for (let i = 0; i < res.rows.length; i++) {
      const row = (res.rows as any)[i];
      edges.push({
        id: row.id,
        sourceId: row.source_id,
        targetId: row.target_id,
        relation: row.relation,
        weight: row.weight,
        docId: row.doc_id,
        createdAt: row.created_at,
      });
    }
    return edges;
  }

  /**
   * Get full graph for visualization (limit by count or depth could be added later)
   */
  /**
   * Get full graph for visualization (limit by count or depth could be added later)
   */
  async getGraphData(
    docIds?: string[], // Changed from single docId to array
    sessionId?: string,
    agentId?: string,
  ): Promise<{ nodes: KGNode[]; edges: KGEdge[] }> {
    let edgesQuery = 'SELECT * FROM kg_edges';
    let conditions: string[] = [];
    let queryParams: any[] = [];

    // Filter by Active Docs (if provided)
    if (docIds && docIds.length > 0) {
      const placeholders = docIds.map(() => '?').join(',');
      conditions.push(`doc_id IN (${placeholders})`);
      queryParams.push(...docIds);
    }

    // Filter by Session (if provided) - Explicit session edges (OR)
    if (sessionId) {
      conditions.push('(session_id = ?)');
      queryParams.push(sessionId);
    }

    // Filter by Agent (if provided) - Agent specific edges (OR)
    if (agentId) {
      conditions.push('(agent_id = ?)');
      queryParams.push(agentId);
    }

    // 🛡️ Strict Isolation: Explicit allow-all if no filters (Global View)
    // if (conditions.length === 0) {
    //   return { nodes: [], edges: [] };
    // }

    if (conditions.length > 0) {
      edgesQuery += ` WHERE ${conditions.join(' OR ')}`;
    }

    const resEdges = await db.execute(edgesQuery, queryParams);
    const edges: KGEdge[] = [];
    const nodeIds = new Set<string>();

    if (resEdges.rows) {
      for (let i = 0; i < resEdges.rows.length; i++) {
        const row = (resEdges.rows as any)[i];
        edges.push({
          id: row.id,
          sourceId: row.source_id,
          targetId: row.target_id,
          relation: row.relation,
          weight: row.weight,
          docId: row.doc_id,
          createdAt: row.created_at,
        });
        nodeIds.add(row.source_id);
        nodeIds.add(row.target_id);
      }
    }

    // If filtering by docId, only get relevant nodes
    let nodes: KGNode[] = [];
    // Only fetch relevant nodes if we are filtering by doc or session
    if ((docIds && docIds.length > 0) || sessionId || agentId) {
      if (nodeIds.size > 0) {
        // Fetch nodes by IDs
        const placeholders = Array.from(nodeIds)
          .map(() => '?')
          .join(',');
        const resNodes = await db.execute(
          `SELECT * FROM kg_nodes WHERE id IN (${placeholders})`,
          Array.from(nodeIds),
        );
        if (resNodes.rows) {
          for (let i = 0; i < resNodes.rows.length; i++) {
            const row = (resNodes.rows as any)[i];
            nodes.push({
              id: row.id,
              name: row.name,
              type: row.type,
              metadata: row.metadata ? JSON.parse(row.metadata) : {},
              createdAt: row.created_at,
            });
          }
        }
      }
    } else {
      // Full graph gets all nodes (or maybe limit this later?)
      nodes = await this.getAllNodes();
    }

    return { nodes, edges };
  }


  // ==========================================
  // Tag Operations
  // ==========================================

  async createTag(name: string, color: string = '#6366f1'): Promise<Tag> {
    const id = generateId();
    const createdAt = Date.now();
    await db.execute('INSERT INTO tags (id, name, color, created_at) VALUES (?, ?, ?, ?)', [
      id,
      name,
      color,
      createdAt,
    ]);
    return { id, name, color, createdAt };
  }

  async getAllTags(): Promise<Tag[]> {
    const res = await db.execute('SELECT * FROM tags ORDER BY created_at DESC');
    if (!res.rows) return [];
    const tags: Tag[] = [];
    for (let i = 0; i < res.rows.length; i++) {
      const row = (res.rows as any)[i];
      tags.push({
        id: row.id,
        name: row.name,
        color: row.color,
        createdAt: row.created_at,
      });
    }
    return tags;
  }

  async attachTagToDoc(docId: string, tagId: string): Promise<void> {
    const createdAt = Date.now();
    // Ignore unique constraint violation if already exists
    await db.execute(
      'INSERT OR IGNORE INTO document_tags (doc_id, tag_id, created_at) VALUES (?, ?, ?)',
      [docId, tagId, createdAt],
    );
  }

  async removeTagFromDoc(docId: string, tagId: string): Promise<void> {
    await db.execute('DELETE FROM document_tags WHERE doc_id = ? AND tag_id = ?', [docId, tagId]);
  }

  async getTagsForDoc(docId: string): Promise<Tag[]> {
    const res = await db.execute(
      `SELECT t.* FROM tags t 
             JOIN document_tags dt ON t.id = dt.tag_id 
             WHERE dt.doc_id = ?`,
      [docId],
    );
    if (!res.rows) return [];
    const tags: Tag[] = [];
    for (let i = 0; i < res.rows.length; i++) {
      const row = (res.rows as any)[i];
      tags.push({
        id: row.id,
        name: row.name,
        color: row.color,
        createdAt: row.created_at,
      });
    }
    return tags;
  }

  async deleteTag(tagId: string): Promise<void> {
    await db.execute('DELETE FROM tags WHERE id = ?', [tagId]);
  }
}

export const graphStore = new GraphStore();
