/**
 * Benchmark Runner - 性能基准测试执行器
 * 
 * 功能：
 * 1. 运行 SQLite CRUD 基准测试
 * 2. 运行 Stream 解析基准测试
 * 3. 运行 RAG 检索基准测试
 * 4. 检测性能退化
 * 5. 保存历史记录
 */

import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

// ============================================================================
// Types
// ============================================================================

export interface BenchmarkConfig {
  name: string;
  description: string;
  testFile: string;
  testNamePattern?: string;
  iterations: number;
  warmupRuns: number;
  thresholds: {
    p95: number;
    p99: number;
    mean: number;
  };
}

export interface BenchmarkResult {
  name: string;
  description?: string;
  iterations: number;
  meanMs: number;
  medianMs: number;
  minMs: number;
  maxMs: number;
  p95Ms: number;
  p99Ms: number;
  stdDev: number;
  thresholds: { p95: number; p99: number; mean: number };
  regression: boolean;
  severity?: 'minor' | 'moderate' | 'critical';
  degradationPercent?: number;
  timestamp: string;
  gitCommit: string;
  gitBranch: string;
  nodeVersion?: string;
  platform?: string;
}

// ============================================================================
// Benchmark Configurations
// ============================================================================

export const BENCHMARK_CONFIGS: BenchmarkConfig[] = [
  {
    name: 'sqlite-crud',
    description: 'SQLite 单次 CRUD 操作延迟',
    testFile: 'src/lib/db/__tests__/benchmark.test.ts',
    testNamePattern: 'CRUD',
    iterations: 1000,
    warmupRuns: 100,
    thresholds: { p95: 10, p99: 20, mean: 5 },
  },
  {
    name: 'vector-search-100',
    description: '100 条数据向量检索延迟',
    testFile: 'src/lib/rag/__tests__/vector-store.benchmark.ts',
    testNamePattern: '100 条',
    iterations: 100,
    warmupRuns: 10,
    thresholds: { p95: 50, p99: 100, mean: 20 },
  },
  {
    name: 'vector-search-1000',
    description: '1000 条数据向量检索延迟',
    testFile: 'src/lib/rag/__tests__/vector-store.benchmark.ts',
    testNamePattern: '1000 条',
    iterations: 100,
    warmupRuns: 10,
    thresholds: { p95: 200, p99: 500, mean: 100 },
  },
  {
    name: 'text-splitting-1kb',
    description: '1KB 文档文本切块延迟',
    testFile: 'src/lib/rag/__tests__/text-splitter.benchmark.ts',
    testNamePattern: '1KB',
    iterations: 500,
    warmupRuns: 50,
    thresholds: { p95: 5, p99: 10, mean: 2 },
  },
  {
    name: 'text-splitting-10kb',
    description: '10KB 文档文本切块延迟',
    testFile: 'src/lib/rag/__tests__/text-splitter.benchmark.ts',
    testNamePattern: '10KB',
    iterations: 200,
    warmupRuns: 20,
    thresholds: { p95: 20, p99: 50, mean: 10 },
  },
  {
    name: 'stream-parsing',
    description: '1000 tokens 流式解析延迟',
    testFile: 'src/lib/llm/__tests__/stream-parser.benchmark.ts',
    testNamePattern: '1000 tokens',
    iterations: 100,
    warmupRuns: 10,
    thresholds: { p95: 20, p99: 50, mean: 10 },
  },
  {
    name: 'store-dispatch-100',
    description: '连续 100 次 Store dispatch 耗时',
    testFile: 'src/store/__tests__/chat-store.benchmark.ts',
    testNamePattern: '100 次',
    iterations: 50,
    warmupRuns: 5,
    thresholds: { p95: 100, p99: 200, mean: 50 },
  },
];

// ============================================================================
// Benchmark Runner
// ============================================================================

export class BenchmarkRunner {
  private historyDir: string;
  private resultsDir: string;

  constructor(
    private projectRoot: string = process.cwd(),
    private verbose: boolean = false
  ) {
    this.historyDir = path.resolve(projectRoot, '.agent/performance-history');
    this.resultsDir = path.resolve(projectRoot, '.agent-test/results/benchmark');
  }

  /**
   * 运行单个基准测试
   */
  async run(config: BenchmarkConfig): Promise<BenchmarkResult> {
    console.log(`\n🔬 运行基准测试: ${config.name}`);
    console.log(`   描述: ${config.description}`);
    
    // 确保目录存在
    this.ensureDirectories();

    // 预热
    if (config.warmupRuns > 0) {
      console.log(`   预热运行: ${config.warmupRuns} 次`);
      await this.warmup(config);
    }

    // 正式运行
    const timings: number[] = [];
    for (let i = 0; i < config.iterations; i++) {
      const start = performance.now();
      await this.runSingleBenchmark(config);
      timings.push(performance.now() - start);
      
      if (this.verbose && (i + 1) % 100 === 0) {
        console.log(`   进度: ${i + 1}/${config.iterations}`);
      }
    }

    const result = this.computeStatistics(timings, config);
    
    // 保存历史记录
    await this.saveHistory(config.name, result);
    
    // 检测退化
    const regression = await this.detectRegression(config.name, result);
    if (regression) {
      result.regression = true;
      result.severity = regression.severity;
      result.degradationPercent = regression.degradationPercent;
    }

    // 打印结果摘要
    this.printResult(result);

    return result;
  }

  /**
   * 运行所有基准测试
   */
  async runAll(): Promise<BenchmarkResult[]> {
    console.log('\n========================================');
    console.log('   性能基准测试套件');
    console.log('========================================');
    
    const results: BenchmarkResult[] = [];
    
    for (const config of BENCHMARK_CONFIGS) {
      try {
        const result = await this.run(config);
        results.push(result);
      } catch (error) {
        console.error(`❌ 基准测试 ${config.name} 失败: ${error}`);
      }
    }
    
    // 打印总结
    this.printSummary(results);
    
    return results;
  }

  /**
   * 列出所有基准测试配置
   */
  listConfigs(): BenchmarkConfig[] {
    return BENCHMARK_CONFIGS;
  }

  /**
   * 获取历史数据
   */
  getHistory(name: string): BenchmarkResult[] {
    const historyPath = path.resolve(this.historyDir, `${name}.json`);
    if (!fs.existsSync(historyPath)) {
      return [];
    }
    return JSON.parse(fs.readFileSync(historyPath, 'utf8'));
  }

  /**
   * 获取性能趋势（最近 10 条）
   */
  getTrend(name: string): { timestamps: string[]; means: number[] } {
    const history = this.getHistory(name).slice(-10);
    return {
      timestamps: history.map(r => r.timestamp),
      means: history.map(r => r.meanMs),
    };
  }

  // =========================================================================
  // Private Methods
  // =========================================================================

  private ensureDirectories(): void {
    if (!fs.existsSync(this.historyDir)) {
      fs.mkdirSync(this.historyDir, { recursive: true });
    }
    if (!fs.existsSync(this.resultsDir)) {
      fs.mkdirSync(this.resultsDir, { recursive: true });
    }
  }

  private async warmup(config: BenchmarkConfig): Promise<void> {
    for (let i = 0; i < config.warmupRuns; i++) {
      await this.runSingleBenchmark(config);
    }
  }

  private async runSingleBenchmark(config: BenchmarkConfig): Promise<void> {
    // 使用 Jest 运行单个基准测试
    const outputFile = path.resolve(this.resultsDir, `${config.name}.json`);
    const args = [
      'npx', 'jest',
      config.testFile,
      `--testNamePattern=${config.testNamePattern}`,
      '--runInBand',
      '--json',
      `--outputFile=${outputFile}`,
    ];

    try {
      execSync(args.join(' '), { 
        cwd: this.projectRoot,
        stdio: this.verbose ? 'inherit' : 'pipe',
      });
    } catch {
      // Jest 测试失败时继续（基准测试本身不关心通过失败）
    }
  }

  private computeStatistics(timings: number[], config: BenchmarkConfig): BenchmarkResult {
    const sorted = [...timings].sort((a, b) => a - b);
    const n = sorted.length;
    
    const mean = timings.reduce((a, b) => a + b, 0) / n;
    const median = n % 2 === 0 
      ? (sorted[n/2 - 1] + sorted[n/2]) / 2 
      : sorted[Math.floor(n/2)];
    
    const p95Index = Math.floor(n * 0.95);
    const p99Index = Math.floor(n * 0.99);
    
    const variance = timings.reduce((sum, t) => sum + Math.pow(t - mean, 2), 0) / n;
    const stdDev = Math.sqrt(variance);

    return {
      name: config.name,
      description: config.description,
      iterations: n,
      meanMs: mean,
      medianMs: median,
      minMs: sorted[0],
      maxMs: sorted[n - 1],
      p95Ms: sorted[p95Index] || sorted[n - 1],
      p99Ms: sorted[p99Index] || sorted[n - 1],
      stdDev,
      thresholds: config.thresholds,
      regression: false,
      timestamp: new Date().toISOString(),
      gitCommit: this.getGitCommit(),
      gitBranch: this.getGitBranch(),
      nodeVersion: process.version,
      platform: process.platform,
    };
  }

  private async detectRegression(
    name: string, 
    current: BenchmarkResult
  ): Promise<{ severity: 'minor' | 'moderate' | 'critical'; degradationPercent: number } | null> {
    const historyPath = path.resolve(this.historyDir, `${name}.json`);
    if (!fs.existsSync(historyPath)) return null;

    const history: BenchmarkResult[] = JSON.parse(fs.readFileSync(historyPath, 'utf8'));
    if (history.length < 3) return null;

    // 滑动窗口均值作为基线（最近 5 条）
    const window = history.slice(-5);
    const baselineMean = window.reduce((sum, r) => sum + r.meanMs, 0) / window.length;
    const degradation = (current.meanMs - baselineMean) / baselineMean;

    if (degradation > 0.1) { // 10% 退化
      return {
        severity: degradation > 0.3 ? 'critical' : degradation > 0.2 ? 'moderate' : 'minor',
        degradationPercent: degradation * 100,
      };
    }

    return null;
  }

  private async saveHistory(name: string, result: BenchmarkResult): Promise<void> {
    const historyPath = path.resolve(this.historyDir, `${name}.json`);
    let history: BenchmarkResult[] = [];
    
    if (fs.existsSync(historyPath)) {
      history = JSON.parse(fs.readFileSync(historyPath, 'utf8'));
    }
    
    history.push(result);
    
    // 只保留最近 100 条记录
    if (history.length > 100) {
      history = history.slice(-100);
    }
    
    fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));
  }

  private getGitCommit(): string {
    try {
      return execSync('git rev-parse HEAD', { cwd: this.projectRoot, encoding: 'utf8' }).trim();
    } catch {
      return 'unknown';
    }
  }

  private getGitBranch(): string {
    try {
      return execSync('git branch --show-current', { cwd: this.projectRoot, encoding: 'utf8' }).trim();
    } catch {
      return 'unknown';
    }
  }

  private printResult(result: BenchmarkResult): void {
    console.log(`   📊 结果:`);
    console.log(`      Mean:  ${result.meanMs.toFixed(2)}ms`);
    console.log(`      P95:   ${result.p95Ms.toFixed(2)}ms`);
    console.log(`      P99:   ${result.p99Ms.toFixed(2)}ms`);
    console.log(`      阈值:  P95<${result.thresholds.p95}ms, Mean<${result.thresholds.mean}ms`);
    
    if (result.regression) {
      console.log(`   ⚠️  性能退化: ${result.degradationPercent?.toFixed(1)}% (${result.severity})`);
    } else {
      const passed = result.p95Ms <= result.thresholds.p95 && result.meanMs <= result.thresholds.mean;
      console.log(`   ${passed ? '✅' : '❌'} 状态: ${passed ? '通过' : '未通过阈值'}`);
    }
  }

  private printSummary(results: BenchmarkResult[]): void {
    console.log('\n========================================');
    console.log('   基准测试总结');
    console.log('========================================');
    
    const passed = results.filter(r => !r.regression).length;
    const regressions = results.filter(r => r.regression).length;
    
    console.log(`\n通过: ${passed}/${results.length}`);
    console.log(`性能退化: ${regressions}`);
    
    if (regressions > 0) {
      console.log('\n⚠️  性能退化详情:');
      results.filter(r => r.regression).forEach(r => {
        console.log(`   - ${r.name}: ${r.degradationPercent?.toFixed(1)}% (${r.severity})`);
      });
    }
  }
}

// ============================================================================
// CLI Entry Point
// ============================================================================

export async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const runner = new BenchmarkRunner(process.cwd(), args.includes('--verbose'));
  
  if (args.includes('--list')) {
    console.log('可用的基准测试:');
    runner.listConfigs().forEach(config => {
      console.log(`  - ${config.name}: ${config.description}`);
    });
    return;
  }

  if (args.includes('--all')) {
    await runner.runAll();
    return;
  }

  // 运行单个指定的基准测试
  const configName = args.find(a => !a.startsWith('--'));
  if (configName) {
    const config = runner.listConfigs().find(c => c.name === configName);
    if (config) {
      await runner.run(config);
    } else {
      console.error(`未找到基准测试: ${configName}`);
      console.log('使用 --list 查看所有基准测试');
    }
    return;
  }

  // 默认：运行所有
  await runner.runAll();
}

// 运行（ESM 兼容）
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(console.error);
}
