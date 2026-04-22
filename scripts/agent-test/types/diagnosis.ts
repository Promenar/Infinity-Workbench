/**
 * 诊断类型定义
 */

import type { TestResult } from './test-report.js';

export type DiagnosisCategory = 
  | 'logic_error' | 'type_error' | 'async_error' 
  | 'mock_error' | 'regression' | 'performance' 
  | 'visual' | 'unknown';

export type Severity = 'low' | 'medium' | 'high' | 'critical';

export interface DiagnosisResult {
  id: string;
  testResult: TestResult;
  category: DiagnosisCategory;
  severity: Severity;
  rootFile: string;
  rootLine: number;
  rootFunction?: string;
  rootContext: string[];
  suggestedFix: string;
  fixConfidence: number;
  timestamp: string;
  processingTimeMs: number;
}
