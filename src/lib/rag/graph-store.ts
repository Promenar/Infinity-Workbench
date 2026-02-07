import { db } from '../db';
import { generateId } from '../utils/id-generator';

export interface KGNode {
  id: string;
  name: string;
  type: string; // 'concept'|'person'|'org'|'location'...
  metadata?: Record<string, any>;
  createdAt: number;
}

export interface KGEdge {
  id: string;
  sourceId: string;
  targetId: string;
  relation: string;
  weight: number;
  docId?: string;
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

  /**
   * Upsert a node (create if not exists by name)
   */
  async upsertNode(
    name: string,
    type: string = 'concept',
    metadata: any = {},
    scope?: { sessionId?: string; agentId?: string },
  ): Promise<string> {
    try {
      // Check if exists (scope aware)
      // Note: We query SELECT * to get existing metadata/type for merging
      let query = 'SELECT * FROM kg_nodes WHERE name = ?';
      const params: any[] = [name];

      const existing = await db.execute(query, params);

      if (existing.rows && existing.rows.length > 0) {
        const row = (existing.rows as any)[0];
        const id = row.id;

        // --- MERGE LOGIC START ---
        // 1. Parsing old metadata
        let oldMetadata = {};
        try {
          oldMetadata = row.metadata ? JSON.parse(row.metadata) : {};
        } catch (e) {
          console.warn('[GraphStore] Failed to parse existing metadata for merge', e);
        }

        const mergedMetadata = { ...oldMetadata };

        // 2. Merge metadata properties
        if (metadata) {
          Object.keys(metadata).forEach((key) => {
            const newVal = metadata[key];
            const oldVal = (mergedMetadata as any)[key];

            if (Array.isArray(newVal) && Array.isArray(oldVal)) {
              // Array: Combine and Unique (e.g. aliases, roles)
              const combined = [...oldVal, ...newVal];
              const unique = combined.filter((item, index, self) =>
                index === self.findIndex((t) => (
                  JSON.stringify(t) === JSON.stringify(item)
                ))
              );
              (mergedMetadata as any)[key] = unique;
            } else if (newVal !== undefined && newVal !== null) {
              // Simple value: Overwrite (Last Write Wins)
              // This allows updating 'description', 'age', etc.
              (mergedMetadata as any)[key] = newVal;
            }
          });
        }

        // 3. Update Type
        // Logic: specific type replaces generic 'concept', otherwise overwrite
        let updatedType = row.type;
        if (type && type !== 'concept') {
          updatedType = type;
        }

        // 4. Update DB
        await db.execute(
          'UPDATE kg_nodes SET type = ?, metadata = ?, updated_at = ? WHERE id = ?',
          [updatedType, JSON.stringify(mergedMetadata), Date.now(), id]
        );
        // --- MERGE LOGIC END ---

        return id;
      }

      // Create new
      const id = generateId();
      const createdAt = Date.now();
      await db.execute(
        'INSERT INTO kg_nodes (id, name, type, metadata, created_at, session_id, agent_id) VALUES (?, ?, ?, ?, ?, ?, ?)',
        [
          id,
          name,
          type,
          JSON.stringify(metadata),
          createdAt,
          scope?.sessionId || null,
          scope?.agentId || null,
        ],
      );
      return id;
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
      // Update edges where source is the FROM node
      await db.execute(
        'UPDATE kg_edges SET source_id = ? WHERE source_id = ?',
        [targetId, sourceId]
      );
      // Update edges where source is the TO node
      await db.execute(
        'UPDATE kg_edges SET target_id = ? WHERE target_id = ?',
        [targetId, sourceId]
      );

      // Note: This might create self-loops or duplicate edges.
      // For now we allow them, or the UI visualization handles them.
      // Ideally we should cleanup duplicates here, but it requires complex query.

      // 3. Merge Metadata
      const sourceRes = await db.execute('SELECT * FROM kg_nodes WHERE id = ?', [sourceId]);
      if (sourceRes.rows && sourceRes.rows.length > 0) {
        const sourceNode = (sourceRes.rows as any)[0];

        let sourceMeta = {};
        let targetMeta = {};
        try { sourceMeta = sourceNode.metadata ? JSON.parse(sourceNode.metadata) : {}; } catch (e) { }
        try { targetMeta = targetNode.metadata ? JSON.parse(targetNode.metadata) : {}; } catch (e) { }

        const mergedMeta = { ...targetMeta };

        // Simple merge strategy: append arrays, keep existing target values for scalars
        Object.keys(sourceMeta).forEach((key) => {
          const sourceVal = (sourceMeta as any)[key];
          const targetVal = (mergedMeta as any)[key];

          if (Array.isArray(sourceVal)) {
            const existingArr = Array.isArray(targetVal) ? targetVal : [];
            // Combine and dedup
            const combined = [...existingArr, ...sourceVal];
            (mergedMeta as any)[key] = Array.from(new Set(combined.map((i) => JSON.stringify(i)))).map((s) => JSON.parse(s));
          } else if (targetVal === undefined || targetVal === null) {
            // Only fill if missing in target
            (mergedMeta as any)[key] = sourceVal;
          }
        });

        await db.execute(
          'UPDATE kg_nodes SET metadata = ?, updated_at = ? WHERE id = ?',
          [JSON.stringify(mergedMeta), Date.now(), targetId]
        );
      }

      // 4. Delete Source Node
      await db.execute('DELETE FROM kg_nodes WHERE id = ?', [sourceId]);

    } catch (e) {
      console.error('Failed to merge KG nodes:', e);
      throw e;
    }
  }

  /**
   * Create an edge between two nodes
   */
  async createEdge(
    sourceId: string,
    targetId: string,
    relation: string,
    docId?: string,
    weight: number = 1.0,
    scope?: { sessionId?: string; agentId?: string },
  ): Promise<string> {
    try {
      const id = generateId();
      const createdAt = Date.now();
      await db.execute(
        'INSERT INTO kg_edges (id, source_id, target_id, relation, weight, doc_id, created_at, session_id, agent_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [
          id,
          sourceId,
          targetId,
          relation,
          weight,
          docId || null,
          createdAt,
          scope?.sessionId || null,
          scope?.agentId || null,
        ],
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
