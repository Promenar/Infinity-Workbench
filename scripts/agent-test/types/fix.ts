/**
 * 修复类型定义
 */

import type { DiagnosisResult } from './diagnosis.js';

export interface FixResult {
  id: string;
  diagnosis: DiagnosisResult;
  status: 'applied' | 'rolled_back' | 'needs_manual' | 'skipped';
  filesModified: FileModification[];
  verificationPassed: boolean;
  rollbackNeeded: boolean;
  backupPaths: string[];
  attemptedAt: string;
  completedAt?: string;
  durationMs: number;
}

export interface FileModification {
  filePath: string;
  operation: 'create' | 'modify' | 'delete';
  diff: string;
  backupPath?: string;
}
