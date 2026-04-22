/**
 * Jest JSON 报告解析器
 */

import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';
import type { 
  TestRunReport, 
  TestResult, 
  TestError,
  ReportMeta,
  TestSummary,
  CoverageReport,
  CoverageMetric,
} from '../types/test-report.js';
import { generateTimestampId } from '../utils/id-generator.js';

// Jest JSON 报告的原始格式
interface JestJsonReport {
  success: boolean;
  startTime: number;
  numTotalTestSuites: number;
  numTotalTests: number;
  numPassedTests: number;
  numFailedTests: number;
  numPendingTests: number;
  testResults: JestTestResult[];
  coverageMap?: Record<string, unknown>;
}

interface JestTestResult {
  assertionResults: JestAssertionResult[];
  startTime: number;
  endTime: number;
  name: string;
  status: 'passed' | 'failed' | 'skipped' | 'pending';
}

interface JestAssertionResult {
  title: string;
  status: 'passed' | 'failed' | 'skipped' | 'pending';
  ancestorTitles: string[];
  failureMessages: string[];
  location?: { line: number; column: number };
  duration?: number;
}

/**
 * 解析 Jest JSON 报告
 */
export function parseJestJsonReport(reportPath: string): TestRunReport {
  if (!fs.existsSync(reportPath)) {
    throw new Error(`报告文件不存在: ${reportPath}`);
  }

  const rawReport = JSON.parse(fs.readFileSync(reportPath, 'utf8')) as JestJsonReport;
  
  // 提取 Git 信息
  const gitInfo = getGitInfo();
  
  // 转换测试结果
  const results: TestResult[] = [];
  let maxDuration = 0;
  
  for (const suite of rawReport.testResults) {
    const suiteName = suite.name;
    const suiteDuration = suite.endTime - suite.startTime;
    maxDuration = Math.max(maxDuration, suiteDuration);
    
    for (const assertion of suite.assertionResults) {
      const testName = assertion.ancestorTitles.concat(assertion.title).join(' > ');
      const status = mapStatus(assertion.status);
      const duration = assertion.duration ?? 0;
      
      let error: TestError | undefined;
      if (assertion.failureMessages.length > 0) {
        error = parseError(assertion.failureMessages[0]);
      }
      
      results.push({
        suiteName,
        testName,
        status,
        duration,
        error,
        filePath: extractFilePath(suite.name),
        lineNumber: assertion.location?.line,
      });
    }
  }
  
  const failedTests = results.filter(t => t.status === 'failed');
  
  // 解析覆盖率
  let coverage: CoverageReport | undefined;
  if (rawReport.coverageMap) {
    coverage = parseCoverage(rawReport.coverageMap);
  }
  
  return {
    meta: {
      timestamp: new Date(rawReport.startTime).toISOString(),
      gitBranch: gitInfo.branch,
      gitCommit: gitInfo.commit,
      nodeVersion: process.version,
      platform: process.platform,
      testDuration: maxDuration,
      runner: 'jest',
    },
    summary: {
      totalTests: rawReport.numTotalTests,
      passed: rawReport.numPassedTests,
      failed: rawReport.numFailedTests,
      skipped: rawReport.numPendingTests,
      timedOut: 0,
      passRate: rawReport.numTotalTests > 0 
        ? (rawReport.numPassedTests / rawReport.numTotalTests) * 100 
        : 100,
    },
    coverage,
    results,
    failedTests,
  };
}

/**
 * 映射 Jest 状态到标准状态
 */
function mapStatus(status: string): TestResult['status'] {
  switch (status) {
    case 'passed': return 'passed';
    case 'failed': return 'failed';
    case 'skipped':
    case 'pending': return 'skipped';
    default: return 'skipped';
  }
}

/**
 * 解析错误信息
 */
function parseError(message: string): TestError {
  // 尝试解析 "expected X but received Y" 格式
  const match = message.match(/Expected:\s*([^\n]+)\n\s*Received:\s*([^\n]+)/);
  
  return {
    message: message.split('\n')[0],
    stack: message,
    type: detectErrorType(message),
    expected: match?.[1],
    received: match?.[2],
  };
}

/**
 * 检测错误类型
 */
function detectErrorType(message: string): TestError['type'] {
  if (message.includes('Timeout')) return 'timeout';
  if (message.includes('SyntaxError') || message.includes('Cannot find module')) return 'compile';
  if (message.includes('Expected')) return 'assertion';
  if (message.includes('async') || message.includes('Promise')) return 'async_error';
  return 'unknown';
}

/**
 * 从路径提取文件名
 */
function extractFilePath(fullPath: string): string {
  // Jest 输出的可能是绝对路径或相对路径
  const parts = fullPath.split(path.sep);
  // 返回最后两部分以保持可识别性
  return parts.slice(-2).join(path.sep);
}

/**
 * 解析覆盖率数据
 */
function parseCoverage(coverageMap: Record<string, unknown>): CoverageReport {
  // Jest 的覆盖率格式可能因版本而异，这里做一个基本解析
  // 实际项目中可能需要根据具体格式调整
  const result: CoverageReport = {
    lines: { total: 0, covered: 0, skipped: 0, pct: 0 },
    branches: { total: 0, covered: 0, skipped: 0, pct: 0 },
    functions: { total: 0, covered: 0, skipped: 0, pct: 0 },
    statements: { total: 0, covered: 0, skipped: 0, pct: 0 },
  };
  
  // 尝试从 coverageMap 中提取数据
  // 这是一个简化实现，实际可能需要更复杂的解析
  try {
    const coverageData = coverageMap['global'] as Record<string, { total: number; covered: number }> | undefined;
    if (coverageData) {
      for (const key of ['lines', 'branches', 'functions', 'statements'] as const) {
        const data = coverageData[key];
        if (data) {
          result[key] = {
            total: data.total,
            covered: data.covered,
            skipped: 0,
            pct: data.total > 0 ? (data.covered / data.total) * 100 : 100,
          };
        }
      }
    }
  } catch {
    // 忽略覆盖率解析错误
  }
  
  return result;
}

/**
 * 获取 Git 信息
 */
function getGitInfo(): { branch: string; commit: string } {
  try {
    const branch = execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf8' }).trim();
    const commit = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
    return { branch, commit };
  } catch {
    return { branch: 'unknown', commit: 'unknown' };
  }
}

/**
 * 生成报告 ID
 */
export function generateReportId(): string {
  return generateTimestampId();
}
