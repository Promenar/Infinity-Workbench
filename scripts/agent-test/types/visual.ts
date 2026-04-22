/**
 * 视觉测试类型定义
 */

export interface VisualSnapshotResult {
  id: string;
  testName: string;
  baselinePath: string;
  currentPath?: string;
  diffPath?: string;
  match: boolean;
  pixelDiffPercent: number;
  diffThreshold: number;
  capturedAt: string;
}

export interface VisualRegressionConfig {
  baselineDir: string;
  snapshotDir: string;
  diffDir: string;
  threshold: number;
  ignoreElements?: string[];
}
