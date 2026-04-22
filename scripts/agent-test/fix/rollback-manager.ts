/**
 * Rollback Manager - 回滚管理器
 * 
 * 职责：
 * 1. 管理代码修改的回滚
 * 2. 维护回滚历史
 * 3. 批量回滚
 * 4. 自动清理过期备份
 */

import fs from 'fs';
import path from 'path';

// ============================================================================
// Types
// ============================================================================

export interface RollbackEntry {
  id: string;
  timestamp: string;
  description: string;
  changes: RollbackChange[];
  status: 'pending' | 'applied' | 'rolled_back' | 'failed';
}

export interface RollbackChange {
  filePath: string;
  backupPath: string;
  originalContent?: string;
}

export interface BatchRollbackResult {
  success: boolean;
  rolledBack: string[];
  failed: { path: string; error: string }[];
}

// ============================================================================
// Rollback Manager
// ============================================================================

export class RollbackManager {
  private historyDir: string;
  private backupDir: string;
  private projectRoot: string;
  private historyFile: string;

  constructor(projectRoot: string = process.cwd()) {
    this.projectRoot = projectRoot;
    this.historyDir = path.resolve(projectRoot, '.agent-test/rollback-history');
    this.backupDir = path.resolve(projectRoot, '.agent-test/backups');
    this.historyFile = path.resolve(this.historyDir, 'rollback-history.json');
    
    this.ensureDirectories();
  }

  /**
   * 记录一次修改（用于后续可能的回滚）
   */
  recordChange(change: RollbackChange, description: string = ''): string {
    const id = `rollback-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    
    const entry: RollbackEntry = {
      id,
      timestamp: new Date().toISOString(),
      description,
      changes: [change],
      status: 'applied',
    };

    this.saveEntry(entry);
    return id;
  }

  /**
   * 批量记录修改
   */
  recordBatch(changes: RollbackChange[], description: string = ''): string {
    const id = `rollback-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    
    const entry: RollbackEntry = {
      id,
      timestamp: new Date().toISOString(),
      description,
      changes,
      status: 'applied',
    };

    this.saveEntry(entry);
    return id;
  }

  /**
   * 执行回滚
   */
  rollback(entryId: string): { success: boolean; message: string } {
    const entry = this.loadEntry(entryId);
    
    if (!entry) {
      return {
        success: false,
        message: `未找到回滚记录: ${entryId}`,
      };
    }

    const results: { success: boolean; path: string; error?: string }[] = [];
    
    for (const change of entry.changes) {
      const result = this.rollbackSingle(change);
      results.push(result);
    }

    // 更新状态
    entry.status = results.every(r => r.success) ? 'rolled_back' : 'failed';
    this.saveEntry(entry);

    const successCount = results.filter(r => r.success).length;
    const failCount = results.filter(r => !r.success).length;

    return {
      success: failCount === 0,
      message: `已回滚 ${successCount}/${entry.changes.length} 个文件${
        failCount > 0 ? `，${failCount} 个失败` : ''
      }`,
    };
  }

  /**
   * 批量回滚最近的 N 次修改
   */
  rollbackRecent(count: number = 1): BatchRollbackResult {
    const history = this.loadHistory();
    const recent = history.filter(e => e.status === 'applied').slice(-count);
    
    const rolledBack: string[] = [];
    const failed: { path: string; error: string }[] = [];

    for (const entry of recent) {
      for (const change of entry.changes) {
        const result = this.rollbackSingle(change);
        if (result.success) {
          rolledBack.push(change.filePath);
        } else {
          failed.push({ path: change.filePath, error: result.error || 'Unknown error' });
        }
      }
      entry.status = 'rolled_back';
      this.saveEntry(entry);
    }

    return {
      success: failed.length === 0,
      rolledBack: [...new Set(rolledBack)],
      failed,
    };
  }

  /**
   * 获取回滚历史
   */
  getHistory(): RollbackEntry[] {
    return this.loadHistory();
  }

  /**
   * 获取最近的回滚记录
   */
  getRecentHistory(count: number = 10): RollbackEntry[] {
    return this.loadHistory().slice(-count);
  }

  /**
   * 删除回滚记录（不执行实际回滚）
   */
  removeHistory(entryId: string): boolean {
    const history = this.loadHistory();
    const filtered = history.filter(e => e.id !== entryId);
    
    if (filtered.length === history.length) {
      return false;
    }

    fs.writeFileSync(this.historyFile, JSON.stringify(filtered, null, 2), 'utf8');
    return true;
  }

  /**
   * 清理过期记录
   */
  cleanup(days: number = 30): number {
    const history = this.loadHistory();
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    
    const filtered = history.filter(e => {
      const entryTime = new Date(e.timestamp).getTime();
      return entryTime >= cutoff || e.status === 'applied';
    });

    const deleted = history.length - filtered.length;
    fs.writeFileSync(this.historyFile, JSON.stringify(filtered, null, 2), 'utf8');
    
    return deleted;
  }

  /**
   * 获取回滚统计
   */
  getStats(): {
    total: number;
    applied: number;
    rolledBack: number;
    failed: number;
  } {
    const history = this.loadHistory();
    
    return {
      total: history.length,
      applied: history.filter(e => e.status === 'applied').length,
      rolledBack: history.filter(e => e.status === 'rolled_back').length,
      failed: history.filter(e => e.status === 'failed').length,
    };
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private ensureDirectories(): void {
    if (!fs.existsSync(this.historyDir)) {
      fs.mkdirSync(this.historyDir, { recursive: true });
    }
    if (!fs.existsSync(this.backupDir)) {
      fs.mkdirSync(this.backupDir, { recursive: true });
    }
  }

  private loadHistory(): RollbackEntry[] {
    if (!fs.existsSync(this.historyFile)) {
      return [];
    }
    
    try {
      return JSON.parse(fs.readFileSync(this.historyFile, 'utf8'));
    } catch {
      return [];
    }
  }

  private loadEntry(entryId: string): RollbackEntry | null {
    const history = this.loadHistory();
    return history.find(e => e.id === entryId) || null;
  }

  private saveEntry(entry: RollbackEntry): void {
    const history = this.loadHistory();
    const existingIndex = history.findIndex(e => e.id === entry.id);
    
    if (existingIndex >= 0) {
      history[existingIndex] = entry;
    } else {
      history.push(entry);
    }

    fs.writeFileSync(this.historyFile, JSON.stringify(history, null, 2), 'utf8');
  }

  private rollbackSingle(change: RollbackChange): { success: boolean; error?: string } {
    try {
      if (change.backupPath && fs.existsSync(change.backupPath)) {
        // 从备份文件恢复
        const backupContent = fs.readFileSync(change.backupPath, 'utf8');
        fs.writeFileSync(change.filePath, backupContent, 'utf8');
        return { success: true };
      } else if (change.originalContent !== undefined) {
        // 直接恢复原始内容
        fs.writeFileSync(change.filePath, change.originalContent, 'utf8');
        return { success: true };
      } else {
        return {
          success: false,
          error: '无备份路径且无原始内容',
        };
      }
    } catch (e: any) {
      return {
        success: false,
        error: e.message,
      };
    }
  }
}
