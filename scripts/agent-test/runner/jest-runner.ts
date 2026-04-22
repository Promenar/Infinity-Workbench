/**
 * Jest 测试执行器
 */

import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { logger } from '../utils/logger.js';

export interface JestRunOptions {
  scope?: string;
  testNamePattern?: string;
  coverage?: boolean;
  jsonOutput: string;
  updateSnapshot?: boolean;
}

export interface JestRunResult {
  success: boolean;
  exitCode: number;
  duration: number;
  output?: string;
}

export class JestRunner {
  constructor(private config: { verbose?: boolean; rootDir?: string } = {}) {}

  async run(options: JestRunOptions): Promise<JestRunResult> {
    const args = this.buildArgs(options);
    const start = Date.now();
    
    logger.debug(`执行命令: npx jest ${args.join(' ')}`);
    
    try {
      const cwd = this.config.rootDir 
        ? path.resolve(this.config.rootDir) 
        : path.resolve(__dirname, '../..');
      
      const output = execSync(`npx jest ${args.join(' ')}`, { 
        encoding: 'utf8',
        stdio: this.config.verbose ? 'inherit' : 'pipe',
        cwd,
      });
      
      return { 
        success: true, 
        exitCode: 0, 
        duration: Date.now() - start,
        output,
      };
    } catch (error: unknown) {
      const execError = error as { status?: number; message?: string; stdout?: string };
      logger.error(`Jest 执行失败: ${execError.message}`);
      
      return { 
        success: false, 
        exitCode: execError.status || 1, 
        duration: Date.now() - start,
        output: execError.stdout,
      };
    }
  }

  private buildArgs(options: JestRunOptions): string[] {
    const args: string[] = [];
    
    // JSON 输出
    args.push('--json');
    args.push(`--outputFile=${options.jsonOutput}`);
    
    // 测试范围
    if (options.scope) {
      // 处理 scope 可能是文件路径或 glob 模式
      if (options.scope.includes('*') || options.scope.endsWith('.ts')) {
        args.push(options.scope);
      } else {
        // 作为测试名称模式
        args.push(`--testPathPattern=${options.scope}`);
      }
    }
    
    // 测试名称匹配
    if (options.testNamePattern) {
      args.push(`--testNamePattern=${options.testNamePattern}`);
    }
    
    // 覆盖率
    if (options.coverage) {
      args.push('--coverage');
    }
    
    // 更新快照
    if (options.updateSnapshot) {
      args.push('--updateSnapshot');
      args.push('-u');
    }
    
    // 简化输出
    if (!this.config.verbose) {
      args.push('--silent');
    }
    
    return args;
  }

  /**
   * 检查 Jest 是否可用
   */
  static checkAvailable(): boolean {
    try {
      execSync('npx jest --version', { stdio: 'pipe' });
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 获取 Jest 版本
   */
  static getVersion(): string | null {
    try {
      return execSync('npx jest --version', { encoding: 'utf8' }).trim();
    } catch {
      return null;
    }
  }
}
