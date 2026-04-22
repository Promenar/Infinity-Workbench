/**
 * 测试报告类型定义
 */

export interface TestResult {
  suiteName: string;
  testName: string;
  status: 'passed' | 'failed' | 'skipped' | 'timedOut';
  duration: number;
  error?: TestError;
  filePath: string;
  lineNumber?: number;
}

export interface TestError {
  message: string;
  stack: string;
  type: 'assertion' | 'runtime' | 'timeout' | 'compile' | 'unknown';
  expected?: string;
  received?: string;
}

export interface TestRunReport {
  meta: ReportMeta;
  summary: TestSummary;
  coverage?: CoverageReport;
  results: TestResult[];
  failedTests: TestResult[];
}

export interface ReportMeta {
  timestamp: string;
  gitBranch: string;
  gitCommit: string;
  nodeVersion: string;
  platform: string;
  testDuration: number;
  runner: 'jest' | 'detox' | 'custom';
}

export interface TestSummary {
  totalTests: number;
  passed: number;
  failed: number;
  skipped: number;
  timedOut: number;
  passRate: number;
}

export interface CoverageReport {
  lines: CoverageMetric;
  branches: CoverageMetric;
  functions: CoverageMetric;
  statements: CoverageMetric;
}

export interface CoverageMetric {
  total: number;
  covered: number;
  skipped: number;
  pct: number;
}
