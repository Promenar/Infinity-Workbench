/**
 * 类型定义统一导出
 */

// 测试报告类型
export type {
  TestResult,
  TestError,
  TestRunReport,
  ReportMeta,
  TestSummary,
  CoverageReport,
  CoverageMetric,
} from './test-report.js';

// 诊断类型
export type {
  DiagnosisCategory,
  Severity,
  DiagnosisResult,
} from './diagnosis.js';

// 修复类型
export type {
  FixResult,
  FileModification,
} from './fix.js';

// 基准测试类型
export type {
  BenchmarkResult,
} from './benchmark.js';

// 视觉测试类型
export type {
  VisualSnapshotResult,
  VisualRegressionConfig,
} from './visual.js';
