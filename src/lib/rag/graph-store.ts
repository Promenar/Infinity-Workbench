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
      let query = 'SELECT id FROM kg_nodes WHERE name = ?';
      const params: any[] = [name];

      // Strategy: 
      // If scope is provided, we prefer specific node. 
      // BUT for now, to keep it simple and connected, we perform a global check first.
      // If we want isolation, we should check AND session_id = ?
      // Let's adopt a "Shared Global + Private Session" stragegy.
      // If found in Global (session_id IS NULL), reuse it.
      // If found in current Session, reuse it.
      // If not found, create in current Session (if scoped) or Global.

      const existing = await db.execute(query, params);

      // Filter in memory for specific logic if needed, or refine query. 
      // Simple logic: deduplicate by name regardless of scope? 
      // NO, "Apple" (Fruit) vs "Apple" (Tech) in different contexts might differ.
      // But for RAG, linking them is often desired.
      // Current decision: Reuse ANY existing node with same name to promote connectivity.
      if (existing.rows && existing.rows.length > 0) {
        return (existing.rows as any)[0].id;
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
    } catch (e) {
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
    } catch (e) {
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
