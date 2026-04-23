/**
 * Error Classifier - 错误分类器
 * 
 * 职责：
 * 1. 分析测试失败信息
 * 2. 匹配已知错误模式
 * 3. 分类错误类型和严重程度
 * 4. 生成诊断建议
 */

import fs from 'fs';
import path from 'path';

// ============================================================================
// Types
// ============================================================================

export type ErrorCategory =
  | 'type_error'
  | 'logic_error'
  | 'async_error'
  | 'mock_error'
  | 'module_error'
  | 'validation_error'
  | 'parse_error'
  | 'react_error'
  | 'regression'
  | 'performance'
  | 'unknown';

export type Severity = 'critical' | 'high' | 'medium' | 'low';

export interface ErrorPattern {
  name: string;
  pattern: RegExp;
  category: ErrorCategory;
  severity: Severity;
  confidence: number;
  likelyCauses: string[];
  suggestedFix: string;
}

export interface ClassifiedError {
  category: ErrorCategory;
  severity: Severity;
  confidence: number;
  patternName: string;
  likelyCauses: string[];
  suggestedFix: string;
  rawError: TestError;
}

export interface TestError {
  message: string;
  stack?: string;
  type?: 'assertion' | 'runtime' | 'timeout' | 'compile' | 'unknown';
  expected?: string;
  received?: string;
}

// ============================================================================
// Error Patterns Library
// ============================================================================

export const ERROR_PATTERNS: ErrorPattern[] = [
  // Type Error: 访问 undefined 属性
  {
    name: '访问 undefined 属性',
    pattern: /Cannot read properties? of (?:undefined|null) \(reading '(\w+)'\)/,
    category: 'type_error',
    severity: 'high',
    confidence: 0.85,
    likelyCauses: [
      '变量未初始化',
      'API 响应数据结构不符合预期',
      '可选链缺失',
      '异步数据未加载完成',
    ],
    suggestedFix: `建议修复方案：

1. **添加可选链**（推荐）：
\`\`\`typescript
// 之前
const value = obj.property;

// 之后
const value = obj?.property;
\`\`\`

2. **使用空值合并**：
\`\`\`typescript
const value = obj?.property ?? defaultValue;
\`\`\`

3. **添加防御性检查**：
\`\`\`typescript
if (obj && obj.property !== undefined) {
  // ...
}
\`\`\``,
  },

  // Type Error: 调用非函数
  {
    name: '调用非函数对象',
    pattern: /(?:TypeError: )?(.+) is not a (?:function|constructor)/,
    category: 'type_error',
    severity: 'high',
    confidence: 0.8,
    likelyCauses: [
      '导入路径错误',
      '模块未正确导出',
      '循环依赖',
      '变量名拼写错误',
    ],
    suggestedFix: `排查步骤：

1. 检查 \`import\` 语句路径是否正确
2. 确认模块是否正确导出该函数
3. 检查 \`package.json\` 中的 \`exports\` 字段
4. 验证循环依赖（使用 \`madge --circular\` 检测）`,
  },

  // Assertion Error
  {
    name: '断言失败',
    pattern: /Expected:? (.+?)\s+Received:? (.+)/s,
    category: 'logic_error',
    severity: 'medium',
    confidence: 0.7,
    likelyCauses: [
      '预期值计算错误',
      'Mock 数据不符合真实场景',
      '边界条件未覆盖',
      '浮点数比较精度问题',
    ],
    suggestedFix: `排查步骤：

1. 分析 Expected 和 Received 的差异
2. 检查被测函数的逻辑是否正确
3. 验证测试数据是否符合预期
4. 对于浮点数，使用 \`toBeCloseTo()\` 而非 \`toBe()\``,
  },

  // Async Timeout
  {
    name: '异步操作超时',
    pattern: /Async callback was not called within timeout of (\d+)ms/,
    category: 'async_error',
    severity: 'high',
    confidence: 0.75,
    likelyCauses: [
      '异步操作未完成',
      'Mock 未正确 resolve/reject',
      'Promise 链断裂',
      'setTimeout/setInterval 未清理',
    ],
    suggestedFix: `修复方案：

1. **增加超时时间**：
\`\`\`typescript
jest.setTimeout(10000);
\`\`\`

2. **使用 fake timers**：
\`\`\`typescript
beforeEach(() => { jest.useFakeTimers(); });
afterEach(() => { jest.useRealTimers(); });
\`\`\`

3. **确保 Promise resolve**：
\`\`\`typescript
await expect(promise).resolves.toBe(value);
\`\`\``,
  },

  // op-sqlite 错误
  {
    name: 'SQLite 操作错误',
    pattern: /op-sqlite: (.+)/,
    category: 'mock_error',
    severity: 'medium',
    confidence: 0.9,
    likelyCauses: [
      'op-sqlite Mock 未正确配置',
      'SQL 语法错误',
      '表结构不匹配',
      '参数绑定错误',
    ],
    suggestedFix: `排查步骤：

1. 检查 \`scripts/mocks/op-sqlite.ts\` Mock 配置
2. 验证 SQL 语句语法
3. 确认表结构定义与查询匹配
4. 检查参数数量和顺序`,
  },

  // Cannot find module
  {
    name: '模块未找到',
    pattern: /Cannot find module ['"]([^'"]+)['"]/,
    category: 'module_error',
    severity: 'critical',
    confidence: 0.95,
    likelyCauses: [
      '包未安装',
      '导入路径错误',
      'TypeScript 路径别名未配置',
      'ESM/CJS 混用问题',
    ],
    suggestedFix: `排查步骤：

1. 运行 \`npm install\` 确保依赖安装
2. 检查 \`import\` 路径是否正确
3. 检查 \`tsconfig.json\` 的 \`paths\` 配置
4. 检查 \`package.json\` 的 \`exports\` 字段`,
  },

  // ZodError
  {
    name: 'Zod 验证错误',
    pattern: /ZodError/,
    category: 'validation_error',
    severity: 'medium',
    confidence: 0.85,
    likelyCauses: [
      '输入数据不符合 schema',
      'schema 定义错误',
      '类型转换失败',
    ],
    suggestedFix: `修复方案：

1. 使用 \`safeParse()\` 而非 \`parse()\`
2. 检查输入数据的完整性和类型
3. 验证 schema 定义是否正确
4. 参考 ZodError 的 \`issues\` 数组定位具体错误`,
  },

  // JSON.parse
  {
    name: 'JSON 解析错误',
    pattern: /Unexpected token (.+) in JSON at position (\d+)/,
    category: 'parse_error',
    severity: 'medium',
    confidence: 0.8,
    likelyCauses: [
      'JSON 字符串格式错误',
      '多余的逗号或引号',
      '包含非转义字符',
    ],
    suggestedFix: `修复方案：

1. 使用 \`jsonrepair\` 库修复损坏的 JSON
2. 检查 JSON 字符串格式
3. 使用 \`JSON.stringify()\` 生成 JSON 而非手动拼接`,
  },

  // Maximum update depth
  {
    name: 'React 更新深度超出限制',
    pattern: /Maximum update depth exceeded/,
    category: 'react_error',
    severity: 'critical',
    confidence: 0.6,
    likelyCauses: [
      'useEffect 依赖数组配置错误',
      '状态更新在渲染中触发',
      'useCallback/useMemo 配置错误',
    ],
    suggestedFix: `修复方案：

1. 检查 \`useEffect\` 依赖数组
2. 使用 \`useCallback\` 包裹回调函数
3. 使用 \`useMemo\` 缓存计算结果
4. 确保状态更新在事件处理器中而非渲染中`,
  },

  // Jest Snapshot Mismatch
  {
    name: '快照不匹配',
    pattern: /Snapshot (.+) mismatched/,
    category: 'regression',
    severity: 'medium',
    confidence: 0.8,
    likelyCauses: [
      'UI 变更未更新快照',
      '预期 UI 变更',
      '随机内容导致差异',
    ],
    suggestedFix: `修复方案：

1. **如果是预期变更**：
\`\`\`bash
npm test -- -u  # 更新快照
\`\`\`

2. **如果是回归问题**：
   - 检查 UI 组件的最近修改
   - 回滚导致变更的提交`,
  },

  // Performance
  {
    name: '性能测试失败',
    pattern: /Performance regression|基准测试.*失败|P\d+.*超过阈值/,
    category: 'performance',
    severity: 'medium',
    confidence: 0.9,
    likelyCauses: [
      '代码变更引入性能退化',
      '测试环境不稳定',
      '数据量增加',
    ],
    suggestedFix: `排查步骤：

1. 检查最近的代码变更
2. 对比历史性能数据
3. 运行多次确认是否稳定
4. 使用 \`git bisect\` 定位引入退化的提交`,
  },
];

// ============================================================================
// Error Classifier
// ============================================================================

export class ErrorClassifier {
  private patterns: ErrorPattern[];

  constructor(customPatterns: ErrorPattern[] = []) {
    this.patterns = [...ERROR_PATTERNS, ...customPatterns];
  }

  /**
   * 分类单个错误
   */
  classify(error: TestError): ClassifiedError {
    for (const pattern of this.patterns) {
      if (pattern.pattern.test(error.message)) {
        return {
          category: pattern.category,
          severity: pattern.severity,
          confidence: pattern.confidence,
          patternName: pattern.name,
          likelyCauses: pattern.likelyCauses,
          suggestedFix: pattern.suggestedFix,
          rawError: error,
        };
      }
    }

    // 默认分类
    return this.classifyUnknown(error);
  }

  /**
   * 分类多个错误
   */
  classifyBatch(errors: TestError[]): ClassifiedError[] {
    return errors.map(e => this.classify(e));
  }

  /**
   * 按类别统计错误
   */
  getStatistics(errors: TestError[]): Record<ErrorCategory, number> {
    const stats: Record<ErrorCategory, number> = {
      type_error: 0,
      logic_error: 0,
      async_error: 0,
      mock_error: 0,
      module_error: 0,
      validation_error: 0,
      parse_error: 0,
      react_error: 0,
      regression: 0,
      performance: 0,
      unknown: 0,
    };

    for (const error of errors) {
      const classified = this.classify(error);
      stats[classified.category]++;
    }

    return stats;
  }

  /**
   * 注册自定义模式
   */
  registerPattern(pattern: ErrorPattern): void {
    this.patterns.push(pattern);
  }

  /**
   * 获取所有已知模式
   */
  getPatterns(): ErrorPattern[] {
    return [...this.patterns];
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private classifyUnknown(error: TestError): ClassifiedError {
    // 基于错误类型进行二次分类
    if (error.type === 'assertion') {
      return {
        category: 'logic_error',
        severity: 'medium',
        confidence: 0.5,
        patternName: '未知断言错误',
        likelyCauses: ['测试断言逻辑可能有问题', '预期值与实际值不匹配'],
        suggestedFix: '检查断言逻辑和测试数据',
        rawError: error,
      };
    }

    if (error.type === 'timeout') {
      return {
        category: 'async_error',
        severity: 'high',
        confidence: 0.6,
        patternName: '未知超时错误',
        likelyCauses: ['异步操作未完成', '网络问题', '测试超时设置过短'],
        suggestedFix: '检查异步操作和超时设置',
        rawError: error,
      };
    }

    if (error.type === 'compile') {
      return {
        category: 'module_error',
        severity: 'critical',
        confidence: 0.7,
        patternName: '未知编译错误',
        likelyCauses: ['代码语法错误', '类型错误', '模块导入问题'],
        suggestedFix: '检查代码语法和类型定义',
        rawError: error,
      };
    }

    return {
      category: 'unknown',
      severity: 'medium',
      confidence: 0.3,
      patternName: '未知错误',
      likelyCauses: ['无法识别的错误类型'],
      suggestedFix: '请人工审查错误信息并定位根因',
      rawError: error,
    };
  }

  /**
   * 格式化分类结果为可读字符串
   */
  formatResult(result: ClassifiedError): string {
    return `
错误分类结果:
───────────────────────────────────────
类别: ${result.category}
严重程度: ${result.severity}
置信度: ${(result.confidence * 100).toFixed(0)}%
匹配模式: ${result.patternName}

可能原因:
${result.likelyCauses.map(c => `  • ${c}`).join('\n')}

建议修复:
${result.suggestedFix}
`;
  }
}

// ============================================================================
// CLI Entry Point
// ============================================================================

export function main(): void {
  const args = process.argv.slice(2);
  
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`
Error Classifier - 错误分类器

用法:
  npx ts-node error-classifier.ts <error-file>
  npx ts-node error-classifier.ts --list

选项:
  --list     列出所有已知错误模式
  --json     输出 JSON 格式结果
  --help     显示帮助信息
`);
    return;
  }

  if (args.includes('--list')) {
    const classifier = new ErrorClassifier();
    console.log('已知错误模式:');
    classifier.getPatterns().forEach(p => {
      console.log(`  - ${p.name} (${p.category}, ${p.severity})`);
    });
    return;
  }

  // 从文件读取错误信息
  if (args.length > 0) {
    const errorFile = args[0];
    try {
      const errorData: TestError = JSON.parse(fs.readFileSync(errorFile, 'utf8'));
      const classifier = new ErrorClassifier();
      const result = classifier.classify(errorData);
      
      const output = args.includes('--json') 
        ? JSON.stringify(result, null, 2)
        : classifier.formatResult(result);
      
      console.log(output);
    } catch (e) {
      console.error(`读取错误文件失败: ${e}`);
    }
    return;
  }

  console.log('使用 --help 查看用法');
}
