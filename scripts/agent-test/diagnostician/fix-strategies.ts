/**
 * Fix Strategies - 修复策略库
 * 
 * 职责：
 * 1. 定义各类错误的修复策略
 * 2. 生成代码修复补丁
 * 3. 验证修复有效性
 */

import fs from 'fs';
import path from 'path';
import { ErrorCategory } from './error-classifier';

// ============================================================================
// Types
// ============================================================================

export interface FixStrategy {
  category: ErrorCategory;
  name: string;
  description: string;
  appliesTo: (error: any) => boolean;
  generateFix: (context: FixContext) => Promise<FixResult>;
  priority: number;
}

export interface FixContext {
  error: any;
  rootFile: string;
  rootLine: number;
  projectRoot: string;
}

export interface FixResult {
  success: boolean;
  changes: FileChange[];
  explanation: string;
  verificationSteps?: string[];
  rollbackSteps?: string[];
}

export interface FileChange {
  filePath: string;
  operation: 'create' | 'modify' | 'delete';
  before?: string;
  after: string;
  diff: string;
}

// ============================================================================
// Fix Strategy Implementations
// ============================================================================

/**
 * 添加可选链策略
 * 
 * 适用错误: Cannot read properties of undefined (reading 'x')
 */
const addOptionalChainingStrategy: FixStrategy = {
  category: 'type_error',
  name: '添加可选链',
  description: '为属性访问添加可选链操作符',
  appliesTo: (error) => /Cannot read properties/.test(error.message),
  priority: 1,
  generateFix: async (context) => {
    const { rootFile, rootLine } = context;
    
    if (!fs.existsSync(rootFile)) {
      return {
        success: false,
        changes: [],
        explanation: `文件不存在: ${rootFile}`,
      };
    }

    const content = fs.readFileSync(rootFile, 'utf8');
    const lines = content.split('\n');
    const targetLine = lines[rootLine - 1];
    
    if (!targetLine) {
      return {
        success: false,
        changes: [],
        explanation: `行 ${rootLine} 不存在`,
      };
    }

    // 检测需要添加可选链的模式
    // 模式: obj.property (不带 ?.)
    const fixedLine = targetLine.replace(
      /(\w+(?:\.\w+)*)\.(\w+)(?!\?)(?![=(])/g,
      '$1?.$2'
    );

    if (fixedLine === targetLine) {
      return {
        success: false,
        changes: [],
        explanation: '未找到需要修复的属性访问',
      };
    }

    lines[rootLine - 1] = fixedLine;
    const newContent = lines.join('\n');

    return {
      success: true,
      changes: [{
        filePath: rootFile,
        operation: 'modify',
        before: targetLine,
        after: fixedLine,
        diff: generateDiff(rootFile, content, newContent),
      }],
      explanation: '已为属性访问添加可选链操作符',
      verificationSteps: [
        '1. 运行相关测试验证修复',
        '2. 检查是否引入新的问题',
        '3. 确认可选链逻辑正确',
      ],
      rollbackSteps: [
        '1. 恢复原始代码行',
        '2. 重新运行测试',
      ],
    };
  },
};

/**
 * 修复未定义的 Mock
 * 
 * 适用错误: Module not found / Mock 未实现
 */
const fixMockStrategy: FixStrategy = {
  category: 'mock_error',
  name: '修复 Mock 实现',
  description: '添加或修复缺失的 Mock 实现',
  appliesTo: (error) => 
    /Cannot find module|Mock.*not.*defined|op-sqlite.*not/.test(error.message) ||
    error.category === 'mock_error',
  priority: 2,
  generateFix: async (context) => {
    const { error, projectRoot } = context;
    
    // 尝试从错误消息中提取模块名
    const moduleMatch = error.message?.match(/Cannot find module ['"]([^'"]+)['"]/);
    const moduleName = moduleMatch?.[1];
    
    if (!moduleName) {
      return {
        success: false,
        changes: [],
        explanation: '无法从错误信息中提取模块名',
      };
    }

    // 确定 Mock 文件路径
    let mockPath: string;
    if (moduleName.startsWith('@/')) {
      mockPath = path.join(projectRoot, 'scripts/mocks', moduleName.slice(2) + '.ts');
    } else if (moduleName.startsWith('@')) {
      mockPath = path.join(projectRoot, 'scripts/mocks', moduleName.slice(1) + '.ts');
    } else {
      const packageName = moduleName.split('/')[0];
      mockPath = path.join(projectRoot, 'scripts/mocks', packageName + '.ts');
    }

    if (fs.existsSync(mockPath)) {
      // Mock 文件存在，可能需要添加缺失的实现
      return {
        success: false,
        changes: [],
        explanation: `Mock 文件已存在: ${mockPath}\n请手动添加缺失的实现`,
      };
    }

    // 生成新的 Mock 文件
    const template = generateMockTemplate(moduleName);
    
    return {
      success: true,
      changes: [{
        filePath: mockPath,
        operation: 'create',
        after: template,
        diff: generateCreateDiff(mockPath, template),
      }],
      explanation: `已创建 Mock 文件: ${mockPath}`,
      verificationSteps: [
        '1. 确保 Mock 导出所有必需的方法',
        '2. 运行测试验证 Mock 工作正常',
        '3. 根据需要添加更多实现细节',
      ],
    };
  },
};

/**
 * 更新 Jest 快照
 * 
 * 适用错误: Snapshot mismatch
 */
const updateSnapshotStrategy: FixStrategy = {
  category: 'regression',
  name: '更新快照',
  description: '更新不匹配的 Jest 快照',
  appliesTo: (error) => /Snapshot.*mismatched|Received.*different.*snapshot/.test(error.message),
  priority: 3,
  generateFix: async (context) => {
    const { error, projectRoot } = context;
    
    // 尝试从错误中提取快照文件路径
    const snapshotMatch = error.message?.match(/in\s+([^\s]+\.test\.[^\s]+)/);
    const testFile = snapshotMatch?.[1];
    
    if (!testFile) {
      return {
        success: false,
        changes: [],
        explanation: '无法确定快照所属的测试文件',
      };
    }

    const snapshotDir = path.join(projectRoot, '__snapshots__');
    const snapshotFile = path.join(snapshotDir, path.basename(testFile).replace(/\.(test|spec)\.[^.]+$/, '.snap'));
    
    if (!fs.existsSync(snapshotFile)) {
      return {
        success: false,
        changes: [],
        explanation: `快照文件不存在: ${snapshotFile}`,
      };
    }

    return {
      success: false,
      changes: [],
      explanation: `需要更新快照文件: ${snapshotFile}`,
      verificationSteps: [
        '1. 确认 UI 变更是有意的',
        '2. 运行: npm test -- -u',
        '3. 审查生成的快照变更',
        '4. 提交快照更新',
      ],
    };
  },
};

/**
 * 添加超时配置
 * 
 * 适用错误: Async callback was not called within timeout
 */
const addTimeoutStrategy: FixStrategy = {
  category: 'async_error',
  name: '增加超时时间',
  description: '增加异步测试或操作的超时时间',
  appliesTo: (error) => /timeout|ETIMEDOUT/.test(error.message),
  priority: 4,
  generateFix: async (context) => {
    const { rootFile, rootLine } = context;
    
    if (!fs.existsSync(rootFile)) {
      return {
        success: false,
        changes: [],
        explanation: `文件不存在: ${rootFile}`,
      };
    }

    const content = fs.readFileSync(rootFile, 'utf8');
    
    // 检查是否已有 jest.setTimeout
    if (/jest\.setTimeout|testTimeout/.test(content)) {
      return {
        success: false,
        changes: [],
        explanation: '文件中已有超时配置，请检查现有配置',
      };
    }

    // 生成新的超时配置
    const timeoutConfig = `// 增加超时时间以处理慢速测试
jest.setTimeout(30000);
`;
    
    // 在文件开头或导入之后添加
    const lines = content.split('\n');
    const insertIndex = this.findInsertIndex(lines);
    lines.splice(insertIndex, 0, timeoutConfig);
    
    const newContent = lines.join('\n');
    
    return {
      success: true,
      changes: [{
        filePath: rootFile,
        operation: 'modify',
        before: lines.slice(insertIndex, insertIndex + 1).join('\n'),
        after: timeoutConfig + lines.slice(insertIndex, insertIndex + 1).join('\n'),
        diff: generateDiff(rootFile, content, newContent),
      }],
      explanation: '已添加 30 秒超时配置',
      verificationSteps: [
        '1. 运行测试验证超时问题已解决',
        '2. 如果仍有问题，考虑使用 fake timers',
        '3. 检查是否有异步操作未正确处理',
      ],
    };
  },
};

// ============================================================================
// Fix Strategy Registry
// ============================================================================

export class FixStrategyRegistry {
  private strategies: FixStrategy[] = [];

  constructor() {
    this.registerBuiltInStrategies();
  }

  /**
   * 注册策略
   */
  register(strategy: FixStrategy): void {
    this.strategies.push(strategy);
    this.strategies.sort((a, b) => a.priority - b.priority);
  }

  /**
   * 获取适用的策略
   */
  getApplicable(error: any): FixStrategy[] {
    return this.strategies.filter(s => s.appliesTo(error));
  }

  /**
   * 获取所有策略
   */
  getAll(): FixStrategy[] {
    return [...this.strategies];
  }

  /**
   * 应用修复
   */
  async applyFix(error: any, context: FixContext): Promise<FixResult[]> {
    const applicable = this.getApplicable(error);
    const results: FixResult[] = [];

    for (const strategy of applicable) {
      try {
        const result = await strategy.generateFix(context);
        results.push(result);
        
        // 如果成功找到修复，不再尝试其他策略
        if (result.success) {
          break;
        }
      } catch (e) {
        console.error(`策略 ${strategy.name} 执行失败:`, e);
      }
    }

    return results;
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private registerBuiltInStrategies(): void {
    this.register(addOptionalChainingStrategy);
    this.register(fixMockStrategy);
    this.register(updateSnapshotStrategy);
    this.register(addTimeoutStrategy);
  }

  private findInsertIndex(lines: string[]): number {
    // 跳过顶部的 import 语句和注释
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line || line.startsWith('//') || line.startsWith('/*') || line.startsWith('*')) {
        continue;
      }
      if (line.startsWith('import ') || line.startsWith('require(')) {
        continue;
      }
      return i;
    }
    return 0;
  }
}

// ============================================================================
// Helper Functions
// ============================================================================

function generateDiff(filePath: string, before: string, after: string): string {
  const lines1 = before.split('\n');
  const lines2 = after.split('\n');
  
  // 简单的行对比
  const diff: string[] = [];
  diff.push(`--- ${filePath}`);
  diff.push(`+++ ${filePath}`);
  
  let i = 0, j = 0;
  while (i < lines1.length || j < lines2.length) {
    if (lines1[i] === lines2[j]) {
      diff.push(` ${lines1[i] || ''}`);
      i++;
      j++;
    } else {
      while (i < lines1.length && lines1[i] !== lines2[j]) {
        diff.push(`-${lines1[i] || ''}`);
        i++;
      }
      while (j < lines2.length && lines1[i - 1] !== lines2[j]) {
        diff.push(`+${lines2[j] || ''}`);
        j++;
      }
    }
  }
  
  return diff.join('\n');
}

function generateCreateDiff(filePath: string, content: string): string {
  return `--- /dev/null\n+++ ${filePath}\n${content.split('\n').map(l => `+${l}`).join('\n')}`;
}

function generateMockTemplate(moduleName: string): string {
  return `/**
 * Mock for ${moduleName}
 * 
 * 自动生成 - 请根据需要完善实现
 */

module.exports = {
  // 添加所需的导出
};
`;
}
