/**
 * Stack Parser - 堆栈跟踪解析器
 * 
 * 职责：
 * 1. 解析堆栈跟踪字符串
 * 2. 提取文件名、行号、函数名
 * 3. 识别项目文件 vs 依赖文件
 * 4. 定位根因帧
 * 5. 获取代码上下文
 */

import fs from 'fs';
import path from 'path';

// ============================================================================
// Types
// ============================================================================

export interface StackFrame {
  file: string;
  line: number;
  column: number;
  function: string;
  isProjectFile: boolean;
  raw: string;
}

export interface ParsedStack {
  frames: StackFrame[];
  rootCause: StackFrame | null;
  projectFiles: string[];
  totalFrames: number;
  projectFrameCount: number;
}

// ============================================================================
// Stack Parser
// ============================================================================

export class StackParser {
  private projectRoot: string;

  // 项目文件路径模式
  private projectPatterns: RegExp[] = [
    /\/src\//,
    /\/scripts\//,
    /\/app\//,
    /\/web-client\//,
  ];

  // 依赖文件路径模式（排除）
  private dependencyPatterns: RegExp[] = [
    /\/node_modules\//,
    /\/__mock__/,
    /jest\//,
    /babel\//,
    /metro\//,
  ];

  constructor(projectRoot: string = process.cwd()) {
    this.projectRoot = projectRoot;
  }

  /**
   * 解析堆栈跟踪字符串
   */
  parse(stackTrace: string): ParsedStack {
    if (!stackTrace) {
      return {
        frames: [],
        rootCause: null,
        projectFiles: [],
        totalFrames: 0,
        projectFrameCount: 0,
      };
    }

    const lines = stackTrace.split('\n');
    const frames: StackFrame[] = [];

    for (const line of lines) {
      const frame = this.parseLine(line);
      if (frame) {
        frames.push(frame);
      }
    }

    // 找到根因帧（第一个项目文件帧）
    const projectFrames = frames.filter(f => f.isProjectFile);
    const rootCause = projectFrames[0] || null;
    const projectFiles = [...new Set(projectFrames.map(f => f.file))];

    return {
      frames,
      rootCause,
      projectFiles,
      totalFrames: frames.length,
      projectFrameCount: projectFrames.length,
    };
  }

  /**
   * 解析单行堆栈信息
   * 
   * 支持格式：
   * - at FunctionName (file:line:column)
   * - at file:line:column
   * - Error: message at file:line
   */
  private parseLine(line: string): StackFrame | null {
    // 清理空白
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('Error:')) {
      return null;
    }

    // 格式 1: at functionName (path:line:column)
    const matchWithFn = trimmed.match(
      /at\s+(?:(.+?)\s+)?\((.+?):(\d+):(\d+)\)/
    );

    if (matchWithFn) {
      const [, functionName, file, lineNum, col] = matchWithFn;
      return {
        file: this.normalizePath(file),
        line: parseInt(lineNum, 10),
        column: parseInt(col, 10),
        function: functionName?.trim() || '<anonymous>',
        isProjectFile: this.isProjectFile(file),
        raw: trimmed,
      };
    }

    // 格式 2: at path:line:column
    const matchWithoutFn = trimmed.match(/at\s+(.+?):(\d+):(\d+)/);
    if (matchWithoutFn) {
      const [, file, lineNum, col] = matchWithoutFn;
      return {
        file: this.normalizePath(file),
        line: parseInt(lineNum, 10),
        column: parseInt(col, 10),
        function: '<anonymous>',
        isProjectFile: this.isProjectFile(file),
        raw: trimmed,
      };
    }

    // 格式 3: at functionName (path:line)
    const matchShort = trimmed.match(/at\s+(?:(.+?)\s+)?\((.+?):(\d+)\)/);
    if (matchShort) {
      const [, functionName, file, lineNum] = matchShort;
      return {
        file: this.normalizePath(file),
        line: parseInt(lineNum, 10),
        column: 0,
        function: functionName?.trim() || '<anonymous>',
        isProjectFile: this.isProjectFile(file),
        raw: trimmed,
      };
    }

    return null;
  }

  /**
   * 标准化文件路径
   */
  private normalizePath(filePath: string): string {
    // 移除 file:// 前缀
    let normalized = filePath.replace(/^file:\/\//, '');
    
    // 转换为绝对路径
    if (!path.isAbsolute(normalized)) {
      normalized = path.resolve(this.projectRoot, normalized);
    }
    
    return path.normalize(normalized);
  }

  /**
   * 判断是否为项目文件
   */
  private isProjectFile(filePath: string): boolean {
    const normalized = this.normalizePath(filePath);

    // 检查是否在项目根目录下
    if (!normalized.startsWith(this.projectRoot)) {
      return false;
    }

    // 检查是否为依赖文件
    for (const pattern of this.dependencyPatterns) {
      if (pattern.test(normalized)) {
        return false;
      }
    }

    // 检查是否为项目文件
    for (const pattern of this.projectPatterns) {
      if (pattern.test(normalized)) {
        return true;
      }
    }

    // 额外检查：是否在 src/app/scripts 目录下
    const relativePath = path.relative(this.projectRoot, normalized);
    return (
      relativePath.startsWith('src/') ||
      relativePath.startsWith('app/') ||
      relativePath.startsWith('scripts/')
    );
  }

  /**
   * 获取堆栈帧周围的代码上下文
   */
  getContext(file: string, line: number, contextLines: number = 5): string[] {
    try {
      if (!fs.existsSync(file)) {
        return [`文件不存在: ${file}`];
      }

      const content = fs.readFileSync(file, 'utf8').split('\n');
      const start = Math.max(0, line - contextLines - 1);
      const end = Math.min(content.length, line + contextLines);

      return content.slice(start, end).map((l, i) => {
        const lineNum = start + i + 1;
        const marker = lineNum === line ? '>>>' : '   ';
        const displayLine = l || '(空行)';
        return `${marker} ${lineNum.toString().padStart(4)}: ${displayLine}`;
      });
    } catch {
      return [`无法读取文件: ${file}`];
    }
  }

  /**
   * 从堆栈中提取相关文件列表
   */
  extractProjectFiles(stackTrace: string): string[] {
    const parsed = this.parse(stackTrace);
    return parsed.projectFiles;
  }

  /**
   * 获取调用链摘要
   */
  getCallChain(stackTrace: string, maxFrames: number = 10): string {
    const parsed = this.parse(stackTrace);
    const frames = parsed.frames.slice(0, maxFrames);
    
    return frames.map((frame, i) => {
      const prefix = frame.isProjectFile ? '→' : '·';
      const func = frame.function !== '<anonymous>' ? frame.function : '';
      return `${i + 1}. ${prefix} ${frame.file}:${frame.line} ${func}`.trim();
    }).join('\n');
  }
}

// ============================================================================
// CLI Entry Point
// ============================================================================

export function main(): void {
  const args = process.argv.slice(2);
  
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`
Stack Parser - 堆栈跟踪解析器

用法:
  npx ts-node stack-parser.ts <stack-file>
  npx ts-node stack-parser.ts --sample

选项:
  --sample   显示示例堆栈
  --help     显示帮助信息
`);
    return;
  }

  if (args.includes('--sample')) {
    const sample = `
Error: Something went wrong
    at FunctionName (/Users/promenar/Codex/Nexara/src/lib/llm/stream-parser.ts:42:15)
    at AnotherFunction (/Users/promenar/Codex/Nexara/src/features/chat/ChatScreen.tsx:123:8)
    at render (/Users/promenar/Codex/Nexara/node_modules/react-dom/cjs/react-dom.development.js:13257:16)
    at scheduleUpdateOnFiber (/Users/promenar/Codex/Nexara/node_modules/react-dom/cjs/react-dom.development.js:22865:34)
`;
    console.log('示例堆栈:\n' + sample);
    
    const parser = new StackParser();
    const result = parser.parse(sample);
    
    console.log('\n解析结果:');
    console.log('───────────────────────────────────────');
    console.log(`总帧数: ${result.totalFrames}`);
    console.log(`项目帧数: ${result.projectFrameCount}`);
    console.log('\n根因帧:');
    if (result.rootCause) {
      console.log(`  文件: ${result.rootCause.file}`);
      console.log(`  行号: ${result.rootCause.line}`);
      console.log(`  函数: ${result.rootCause.function}`);
    }
    console.log('\n调用链:');
    console.log(parser.getCallChain(sample));
    return;
  }

  if (args.length > 0) {
    const stackFile = args[0];
    try {
      const stackTrace = fs.readFileSync(stackFile, 'utf8');
      const parser = new StackParser();
      const result = parser.parse(stackTrace);
      
      console.log('堆栈分析结果:');
      console.log('───────────────────────────────────────');
      console.log(`总帧数: ${result.totalFrames}`);
      console.log(`项目帧数: ${result.projectFrameCount}`);
      console.log(`项目文件: ${result.projectFiles.join(', ')}`);
      
      if (result.rootCause) {
        console.log('\n根因位置:');
        console.log(`  ${result.rootCause.file}:${result.rootCause.line}`);
        console.log(`  ${result.rootCause.function}`);
        
        console.log('\n代码上下文:');
        const context = parser.getContext(result.rootCause.file, result.rootCause.line);
        console.log(context.join('\n'));
      }
    } catch (e) {
      console.error(`读取文件失败: ${e}`);
    }
    return;
  }

  console.log('使用 --help 查看用法');
}
