/**
 * 基准测试类型定义
 */

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
}
