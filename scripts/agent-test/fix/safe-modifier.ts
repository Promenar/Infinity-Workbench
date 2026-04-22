/**
 * Safe Modifier - 安全代码修改器
 * 
 * 职责：
 * 1. 备份原始文件
 * 2. 安全地修改代码
 * 3. 验证修改正确性
 * 4. 支持回滚
 */

import fs from 'fs';
import path from 'path';

// ============================================================================
// Types
// ============================================================================

export interface ModifyOptions {
  backup?: boolean;
  dryRun?: boolean;
  verbose?: boolean;
}

export interface ModifyResult {
  success: boolean;
  filePath: string;
  backupPath?: string;
  diff?: string;
  error?: string;
}

export interface RollbackResult {
  success: boolean;
  message: string;
}

// ============================================================================
// Safe Modifier
// ============================================================================

export class SafeModifier {
  private backupDir: string;
  private projectRoot: string;

  constructor(projectRoot: string = process.cwd()) {
    this.projectRoot = projectRoot;
    this.backupDir = path.resolve(projectRoot, '.agent-test/backups');
    this.ensureBackupDir();
  }

  /**
   * 修改文件内容
   * 
   * @param filePath 目标文件路径
   * @param content 新的文件内容
   * @param options 修改选项
   */
  modify(filePath: string, content: string, options: ModifyOptions = {}): ModifyResult {
    const { backup = true, dryRun = false, verbose = false } = options;
    
    // 确保路径是绝对路径
    const absolutePath = path.isAbsolute(filePath) 
      ? filePath 
      : path.resolve(this.projectRoot, filePath);

    // 检查文件是否存在
    if (!fs.existsSync(absolutePath)) {
      return {
        success: false,
        filePath: absolutePath,
        error: `文件不存在: ${absolutePath}`,
      };
    }

    // 生成差异
    const originalContent = fs.readFileSync(absolutePath, 'utf8');
    const diff = this.generateDiff(originalContent, content, absolutePath);

    if (verbose) {
      console.log('生成的差异:');
      console.log(diff);
    }

    if (dryRun) {
      return {
        success: true,
        filePath: absolutePath,
        diff,
      };
    }

    // 备份原始文件
    let backupPath: string | undefined;
    if (backup) {
      backupPath = this.backup(absolutePath);
    }

    // 写入新内容
    try {
      fs.writeFileSync(absolutePath, content, 'utf8');
      
      if (verbose) {
        console.log(`✓ 文件已修改: ${absolutePath}`);
      }

      return {
        success: true,
        filePath: absolutePath,
        backupPath,
        diff,
      };
    } catch (e: any) {
      // 写入失败，尝试恢复备份
      if (backupPath) {
        this.rollbackFromBackup(absolutePath, backupPath);
      }
      
      return {
        success: false,
        filePath: absolutePath,
        backupPath,
        error: e.message,
      };
    }
  }

  /**
   * 替换文件中的文本
   * 
   * @param filePath 目标文件路径
   * @param search 搜索模式（字符串或正则）
   * @param replace 替换文本
   * @param options 修改选项
   */
  replace(
    filePath: string,
    search: string | RegExp,
    replace: string,
    options: ModifyOptions = {}
  ): ModifyResult {
    const absolutePath = path.isAbsolute(filePath)
      ? filePath
      : path.resolve(this.projectRoot, filePath);

    if (!fs.existsSync(absolutePath)) {
      return {
        success: false,
        filePath: absolutePath,
        error: `文件不存在: ${absolutePath}`,
      };
    }

    const content = fs.readFileSync(absolutePath, 'utf8');
    const newContent = content.replace(search, replace);
    
    if (newContent === content) {
      return {
        success: false,
        filePath: absolutePath,
        error: `未找到匹配: ${search}`,
      };
    }

    return this.modify(absolutePath, newContent, options);
  }

  /**
   * 在指定位置插入文本
   * 
   * @param filePath 目标文件路径
   * @param lineNumber 插入行号（1-based）
   * @param text 插入的文本
   * @param options 修改选项
   */
  insert(
    filePath: string,
    lineNumber: number,
    text: string,
    options: ModifyOptions = {}
  ): ModifyResult {
    const absolutePath = path.isAbsolute(filePath)
      ? filePath
      : path.resolve(this.projectRoot, filePath);

    if (!fs.existsSync(absolutePath)) {
      return {
        success: false,
        filePath: absolutePath,
        error: `文件不存在: ${absolutePath}`,
      };
    }

    const lines = fs.readFileSync(absolutePath, 'utf8').split('\n');
    
    if (lineNumber < 1 || lineNumber > lines.length + 1) {
      return {
        success: false,
        filePath: absolutePath,
        error: `行号超出范围: ${lineNumber}`,
      };
    }

    lines.splice(lineNumber - 1, 0, text);
    const newContent = lines.join('\n');

    return this.modify(absolutePath, newContent, options);
  }

  /**
   * 删除指定行
   * 
   * @param filePath 目标文件路径
   * @param startLine 起始行号（1-based）
   * @param endLine 结束行号（1-based，包含）
   * @param options 修改选项
   */
  deleteLines(
    filePath: string,
    startLine: number,
    endLine: number,
    options: ModifyOptions = {}
  ): ModifyResult {
    const absolutePath = path.isAbsolute(filePath)
      ? filePath
      : path.resolve(this.projectRoot, filePath);

    if (!fs.existsSync(absolutePath)) {
      return {
        success: false,
        filePath: absolutePath,
        error: `文件不存在: ${absolutePath}`,
      };
    }

    const lines = fs.readFileSync(absolutePath, 'utf8').split('\n');
    
    if (startLine < 1 || endLine > lines.length || startLine > endLine) {
      return {
        success: false,
        filePath: absolutePath,
        error: `行号范围无效: ${startLine}-${endLine}`,
      };
    }

    lines.splice(startLine - 1, endLine - startLine + 1);
    const newContent = lines.join('\n');

    return this.modify(absolutePath, newContent, options);
  }

  /**
   * 回滚修改
   * 
   * @param filePath 目标文件路径
   * @param backupPath 备份文件路径
   */
  rollback(filePath: string, backupPath: string): RollbackResult {
    const absolutePath = path.isAbsolute(filePath)
      ? filePath
      : path.resolve(this.projectRoot, filePath);

    if (!fs.existsSync(backupPath)) {
      return {
        success: false,
        message: `备份文件不存在: ${backupPath}`,
      };
    }

    try {
      // 读取备份内容
      const backupContent = fs.readFileSync(backupPath, 'utf8');
      
      // 覆盖当前文件
      fs.writeFileSync(absolutePath, backupContent, 'utf8');
      
      return {
        success: true,
        message: `已回滚: ${absolutePath}`,
      };
    } catch (e: any) {
      return {
        success: false,
        message: `回滚失败: ${e.message}`,
      };
    }
  }

  /**
   * 列出所有备份
   */
  listBackups(): { path: string; original: string; time: Date }[] {
    if (!fs.existsSync(this.backupDir)) {
      return [];
    }

    const backups: { path: string; original: string; time: Date }[] = [];
    const files = fs.readdirSync(this.backupDir);

    for (const file of files) {
      const fullPath = path.resolve(this.backupDir, file);
      const stat = fs.statSync(fullPath);
      
      // 格式: original__timestamp.bak
      const match = file.match(/^(.+?)__(.+)\.bak$/);
      if (match) {
        backups.push({
          path: fullPath,
          original: match[1],
          time: new Date(parseInt(match[2], 10)),
        });
      }
    }

    return backups.sort((a, b) => b.time.getTime() - a.time.getTime());
  }

  /**
   * 清理旧备份
   * 
   * @param days 保留最近 N 天的备份
   */
  cleanupOldBackups(days: number = 7): number {
    const backups = this.listBackups();
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    let deleted = 0;

    for (const backup of backups) {
      if (backup.time.getTime() < cutoff) {
        try {
          fs.unlinkSync(backup.path);
          deleted++;
        } catch {
          // 忽略删除失败
        }
      }
    }

    return deleted;
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private ensureBackupDir(): void {
    if (!fs.existsSync(this.backupDir)) {
      fs.mkdirSync(this.backupDir, { recursive: true });
    }
  }

  private backup(filePath: string): string {
    const filename = path.basename(filePath);
    const timestamp = Date.now();
    const backupFilename = `${filename}__${timestamp}.bak`;
    const backupPath = path.resolve(this.backupDir, backupFilename);

    const content = fs.readFileSync(filePath, 'utf8');
    fs.writeFileSync(backupPath, content, 'utf8');

    return backupPath;
  }

  private rollbackFromBackup(filePath: string, backupPath: string): void {
    try {
      const backupContent = fs.readFileSync(backupPath, 'utf8');
      fs.writeFileSync(filePath, backupContent, 'utf8');
    } catch {
      // 回滚失败，无法恢复
    }
  }

  private generateDiff(before: string, after: string, filePath: string): string {
    const lines1 = before.split('\n');
    const lines2 = after.split('\n');
    
    const diff: string[] = [];
    diff.push(`--- ${filePath}`);
    diff.push(`+++ ${filePath}`);

    // 简化：显示前 50 行差异
    const maxLines = 50;
    const displayLines1 = lines1.slice(0, maxLines);
    const displayLines2 = lines2.slice(0, maxLines);

    // 简单行对比
    const commonLines = Math.min(displayLines1.length, displayLines2.length);
    
    for (let i = 0; i < commonLines; i++) {
      if (displayLines1[i] !== displayLines2[i]) {
        diff.push(`@@ -${i + 1} +${i + 1} @@`);
        diff.push(`-${displayLines1[i]}`);
        diff.push(`+${displayLines2[i]}`);
      }
    }

    if (lines1.length > lines2.length) {
      diff.push(`@@ ... +${lines2.length} @@`);
      for (let i = displayLines1.length; i < lines1.length && diff.length < 100; i++) {
        diff.push(`-${lines1[i]}`);
      }
    } else if (lines2.length > lines1.length) {
      diff.push(`@@ ... +${lines2.length} @@`);
      for (let i = displayLines2.length; i < lines2.length && diff.length < 100; i++) {
        diff.push(`+${lines2[i]}`);
      }
    }

    return diff.join('\n');
  }
}
