/**
 * 安全文件操作工具
 */

import fs from 'fs';
import path from 'path';

export interface SafeWriteResult {
  success: boolean;
  backupPath: string;
  diff: string;
  error?: string;
}

export interface FileBackupInfo {
  backupPath: string;
  originalContent: string;
}

/**
 * 安全写入文件
 * 1. 创建备份
 * 2. 生成 diff
 * 3. 写入新内容
 * 4. 验证写入
 */
export function safeWriteFile(
  filePath: string,
  newContent: string,
  originalContent: string
): SafeWriteResult {
  try {
    // 确保目录存在
    const dir = path.dirname(filePath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }

    // 创建备份
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const backupPath = `${filePath}.backup.${timestamp}`;
    fs.writeFileSync(backupPath, originalContent, 'utf8');

    // 生成 diff（简化版）
    const oldLines = originalContent.split('\n');
    const newLines = newContent.split('\n');
    const diffLines: string[] = [];

    const maxLines = Math.max(oldLines.length, newLines.length);
    for (let i = 0; i < maxLines; i++) {
      const oldLine = oldLines[i] ?? '';
      const newLine = newLines[i] ?? '';
      if (oldLine !== newLine) {
        if (oldLine) diffLines.push(`- ${oldLine}`);
        if (newLine) diffLines.push(`+ ${newLine}`);
      }
    }
    const diff = diffLines.join('\n');

    // 写入新内容
    fs.writeFileSync(filePath, newContent, 'utf8');

    // 验证写入
    const verified = fs.readFileSync(filePath, 'utf8');
    if (verified !== newContent) {
      // 回滚
      rollback(filePath, backupPath);
      return {
        success: false,
        backupPath,
        diff,
        error: '验证写入失败，已回滚',
      };
    }

    return { success: true, backupPath, diff };
  } catch (error) {
    return {
      success: false,
      backupPath: '',
      diff: '',
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

/**
 * 从备份恢复文件
 */
export function rollback(filePath: string, backupPath: string): void {
  try {
    if (fs.existsSync(backupPath)) {
      const content = fs.readFileSync(backupPath, 'utf8');
      fs.writeFileSync(filePath, content, 'utf8');
    }
  } catch (error) {
    console.error(`回滚失败: ${filePath}`, error);
  }
}

/**
 * 清理过期备份文件
 */
export function cleanupBackups(filePath: string, maxBackups: number = 5): void {
  try {
    const dir = path.dirname(filePath);
    const basename = path.basename(filePath);
    const backups = fs.readdirSync(dir)
      .filter(f => f.startsWith(basename + '.backup.'))
      .map(f => ({
        name: f,
        path: path.join(dir, f),
        mtime: fs.statSync(path.join(dir, f)).mtime.getTime(),
      }))
      .sort((a, b) => b.mtime - a.mtime);

    // 删除多余的备份
    for (let i = maxBackups; i < backups.length; i++) {
      fs.unlinkSync(backups[i].path);
    }
  } catch {
    // 忽略错误
  }
}
