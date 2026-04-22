/**
 * Diff Engine - 视觉差异对比引擎
 * 
 * 职责：
 * 1. 像素级图像对比
 * 2. 计算差异百分比
 * 3. 生成差异图像
 * 4. 性能优化的大图处理
 */

import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

// ============================================================================
// Types
// ============================================================================

export interface DiffConfig {
  pixelThreshold: number;       // 像素差异阈值 (0-1)
  perceptualThreshold: number; // 感知差异阈值
  outputDir: string;            // 差异图像输出目录
  generateDiffImage: boolean;   // 是否生成差异图像
}

export interface DiffResult {
  status: 'pass' | 'regression' | 'new';
  diffPercentage: number;
  diffPixelCount: number;
  totalPixels: number;
  diffPath?: string;
  analysis?: string;
}

export interface ImageMetadata {
  width: number;
  height: number;
  format: string;
  size: number;
}

// ============================================================================
// Default Configuration
// ============================================================================

export const DEFAULT_DIFF_CONFIG: DiffConfig = {
  pixelThreshold: 0.05,         // 5% 差异阈值
  perceptualThreshold: 0.1,
  outputDir: '/tmp/visual-diffs',
  generateDiffImage: true,
};

// ============================================================================
// Diff Engine
// ============================================================================

export class DiffEngine {
  private config: DiffConfig;

  constructor(config: Partial<DiffConfig> = {}) {
    this.config = { ...DEFAULT_DIFF_CONFIG, ...config };
    this.ensureOutputDir();
  }

  /**
   * 对比两张图像
   */
  async compare(
    baselinePath: string,
    currentPath: string,
    diffName?: string
  ): Promise<DiffResult> {
    // 检查文件是否存在
    if (!fs.existsSync(baselinePath)) {
      return {
        status: 'new',
        diffPercentage: 100,
        diffPixelCount: 0,
        totalPixels: 0,
        analysis: '基线图像不存在',
      };
    }

    if (!fs.existsSync(currentPath)) {
      return {
        status: 'new',
        diffPercentage: 100,
        diffPixelCount: 0,
        totalPixels: 0,
        analysis: '当前图像不存在',
      };
    }

    // 获取图像元数据
    const baselineMeta = this.getImageMetadata(baselinePath);
    const currentMeta = this.getImageMetadata(currentPath);

    // 检查尺寸是否匹配
    if (baselineMeta.width !== currentMeta.width || baselineMeta.height !== currentMeta.height) {
      return {
        status: 'regression',
        diffPercentage: 100,
        diffPixelCount: baselineMeta.width * baselineMeta.height,
        totalPixels: baselineMeta.width * baselineMeta.height,
        analysis: `图像尺寸不匹配: 基线 ${baselineMeta.width}x${baselineMeta.height} vs 当前 ${currentMeta.width}x${currentMeta.height}`,
      };
    }

    // 尝试使用 pixelmatch
    if (this.isPixelmatchAvailable()) {
      return this.compareWithPixelmatch(baselinePath, currentPath, diffName);
    }

    // 回退到简单的文件比较
    return this.compareSimple(baselinePath, currentPath, diffName);
  }

  /**
   * 使用 pixelmatch 进行精确对比
   */
  private compareWithPixelmatch(
    baselinePath: string,
    currentPath: string,
    diffName?: string
  ): DiffResult {
    const diffFilename = diffName 
      ? `${diffName}-diff-${Date.now()}.png`
      : `diff-${Date.now()}.png`;
    const diffPath = path.resolve(this.config.outputDir, diffFilename);

    try {
      const output = execSync(
        `pixelmatch "${baselinePath}" "${currentPath}" "${diffPath}" --threshold 0.1`,
        { encoding: 'utf8' }
      );

      // pixelmatch 输出格式: diffPixelCount totalPixels
      const parts = output.trim().split(' ');
      const diffPixelCount = parseInt(parts[0], 10);
      const totalPixels = parseInt(parts[1], 10);
      const diffPercentage = totalPixels > 0 ? diffPixelCount / totalPixels : 0;

      return {
        status: diffPercentage <= this.config.pixelThreshold ? 'pass' : 'regression',
        diffPercentage,
        diffPixelCount,
        totalPixels,
        diffPath: this.config.generateDiffImage ? diffPath : undefined,
        analysis: this.analyzeDiff(diffPercentage, diffPixelCount),
      };
    } catch (e: any) {
      console.warn(`pixelmatch 执行失败: ${e.message}，回退到简单比较`);
      return this.compareSimple(baselinePath, currentPath, diffName);
    }
  }

  /**
   * 简单对比（基于文件哈希）
   */
  private compareSimple(
    baselinePath: string,
    currentPath: string,
    diffName?: string
  ): DiffResult {
    const baselineHash = this.computeFileHash(baselinePath);
    const currentHash = this.computeFileHash(currentPath);

    const baselineMeta = this.getImageMetadata(baselinePath);
    const totalPixels = baselineMeta.width * baselineMeta.height;

    if (baselineHash === currentHash) {
      return {
        status: 'pass',
        diffPercentage: 0,
        diffPixelCount: 0,
        totalPixels,
        analysis: '图像完全相同',
      };
    }

    // 无法精确计算像素差异，使用文件大小估算
    const baselineSize = fs.statSync(baselinePath).size;
    const currentSize = fs.statSync(currentPath).size;
    const sizeDiff = Math.abs(baselineSize - currentSize);
    const estimatedDiff = Math.min(sizeDiff / baselineSize, 1);

    return {
      status: estimatedDiff > this.config.pixelThreshold ? 'regression' : 'pass',
      diffPercentage: estimatedDiff,
      diffPixelCount: Math.round(totalPixels * estimatedDiff),
      totalPixels,
      analysis: `文件哈希不同，使用大小估算差异: ${(estimatedDiff * 100).toFixed(1)}%`,
    };
  }

  /**
   * 批量对比多个屏幕
   */
  async compareBatch(
    baselines: Map<string, string>, // screenName -> baselinePath
    currents: Map<string, string>,  // screenName -> currentPath
    diffNamePrefix?: string
  ): Promise<Map<string, DiffResult>> {
    const results = new Map<string, DiffResult>();

    for (const [screenName, baselinePath] of baselines) {
      const currentPath = currents.get(screenName);
      
      if (!currentPath) {
        results.set(screenName, {
          status: 'new',
          diffPercentage: 100,
          diffPixelCount: 0,
          totalPixels: 0,
          analysis: '缺少当前截图',
        });
        continue;
      }

      const result = await this.compare(
        baselinePath,
        currentPath,
        diffNamePrefix ? `${diffNamePrefix}-${screenName}` : screenName
      );
      results.set(screenName, result);
    }

    return results;
  }

  /**
   * 获取图像元数据
   */
  getImageMetadata(imagePath: string): ImageMetadata {
    try {
      const stat = fs.statSync(imagePath);
      // 简单的 PNG 头解析获取尺寸
      const buffer = Buffer.alloc(24);
      const fd = fs.openSync(imagePath, 'r');
      fs.readSync(fd, buffer, 0, 24, 0);
      fs.closeSync(fd);

      // PNG: 16-23 是宽高 (big-endian)
      if (buffer[0] === 0x89 && buffer[1] === 0x50) {
        const width = buffer.readUInt32BE(16);
        const height = buffer.readUInt32BE(20);
        return {
          width,
          height,
          format: 'PNG',
          size: stat.size,
        };
      }

      // JPEG
      if (buffer[0] === 0xFF && buffer[1] === 0xD8) {
        return {
          width: 0, // JPEG 需要更复杂的解析
          height: 0,
          format: 'JPEG',
          size: stat.size,
        };
      }

      return {
        width: 0,
        height: 0,
        format: 'Unknown',
        size: stat.size,
      };
    } catch {
      return {
        width: 0,
        height: 0,
        format: 'Unknown',
        size: 0,
      };
    }
  }

  /**
   * 生成差异摘要报告
   */
  generateReport(results: Map<string, DiffResult>): string {
    let report = `# 视觉差异报告\n\n`;
    report += `生成时间: ${new Date().toISOString()}\n\n`;

    const passed = [...results.values()].filter(r => r.status === 'pass').length;
    const regressions = [...results.values()].filter(r => r.status === 'regression').length;
    const newItems = [...results.values()].filter(r => r.status === 'new').length;
    const total = results.size;

    report += `## 摘要\n\n`;
    report += `| 状态 | 数量 |\n`;
    report += `|------|------|\n`;
    report += `| 通过 | ${passed} |\n`;
    report += `| 回归 | ${regressions} |\n`;
    report += `| 新增 | ${newItems} |\n`;
    report += `| 总计 | ${total} |\n\n`;

    if (regressions > 0) {
      report += `## 回归详情\n\n`;
      report += `| 屏幕 | 差异 | 像素差异数 | 分析 |\n`;
      report += `|------|------|-----------|------|\n`;

      for (const [screen, result] of results) {
        if (result.status === 'regression') {
          report += `| ${screen} | ${(result.diffPercentage * 100).toFixed(1)}% | ${result.diffPixelCount} | ${result.analysis} |\n`;
        }
      }
    }

    if (newItems > 0) {
      report += `\n## 新增截图\n\n`;
      for (const [screen, result] of results) {
        if (result.status === 'new') {
          report += `- ${screen}: ${result.analysis}\n`;
        }
      }
    }

    return report;
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private ensureOutputDir(): void {
    if (!fs.existsSync(this.config.outputDir)) {
      fs.mkdirSync(this.config.outputDir, { recursive: true });
    }
  }

  private isPixelmatchAvailable(): boolean {
    try {
      execSync('which pixelmatch', { stdio: 'pipe' });
      return true;
    } catch {
      return false;
    }
  }

  private computeFileHash(filePath: string): string {
    const crypto = require('crypto');
    const hash = crypto.createHash('sha256');
    hash.update(fs.readFileSync(filePath));
    return hash.digest('hex');
  }

  private analyzeDiff(diffPercentage: number, diffPixelCount: number): string {
    if (diffPercentage === 0) {
      return '图像完全相同';
    }
    if (diffPercentage < 0.01) {
      return '几乎相同，可能为渲染噪声';
    }
    if (diffPercentage < 0.05) {
      return '轻微差异，可能为抗锯齿或字体渲染差异';
    }
    if (diffPercentage < 0.1) {
      return '中等差异，可能存在 UI 微调';
    }
    return `显著差异 (${diffPixelCount} 像素)`;
  }
}

// ============================================================================
// CLI Entry Point
// ============================================================================

export function main(): void {
  const args = process.argv.slice(2);
  
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`
Diff Engine - 视觉差异对比引擎

用法:
  npx ts-node diff-engine.ts compare <baseline> <current> [options]
  npx ts-node diff-engine.ts batch <baseline-dir> <current-dir>
  npx ts-node diff-engine.ts report <results-file>

选项:
  --threshold <0-1>      像素差异阈值 (默认: 0.05)
  --output <dir>         差异图像输出目录
  --no-diff-image        不生成差异图像
  --help                 显示帮助
`);
    return;
  }

  const command = args[0];
  const engine = new DiffEngine();

  switch (command) {
    case 'compare': {
      const baselinePath = args[1];
      const currentPath = args[2];
      
      if (!baselinePath || !currentPath) {
        console.error('请提供基线和当前图像路径');
        return;
      }

      const threshold = args.includes('--threshold')
        ? parseFloat(args[args.indexOf('--threshold') + 1])
        : undefined;
      
      if (threshold !== undefined) {
        engine['config'].pixelThreshold = threshold;
      }

      engine.compare(baselinePath, currentPath)
        .then(result => {
          console.log(`状态: ${result.status}`);
          console.log(`差异: ${(result.diffPercentage * 100).toFixed(2)}%`);
          console.log(`像素差异: ${result.diffPixelCount}/${result.totalPixels}`);
          console.log(`分析: ${result.analysis}`);
          
          if (result.diffPath) {
            console.log(`差异图像: ${result.diffPath}`);
          }
        })
        .catch(e => {
          console.error(`对比失败: ${e.message}`);
        });
      break;
    }

    case 'batch': {
      const baselineDir = args[1];
      const currentDir = args[2];
      
      if (!baselineDir || !currentDir) {
        console.error('请提供基线和当前目录');
        return;
      }

      const baselines = new Map<string, string>();
      const currents = new Map<string, string>();

      // 扫描目录
      for (const file of fs.readdirSync(baselineDir)) {
        if (file.endsWith('.png')) {
          baselines.set(file.replace('.png', ''), path.resolve(baselineDir, file));
        }
      }
      for (const file of fs.readdirSync(currentDir)) {
        if (file.endsWith('.png')) {
          currents.set(file.replace('.png', ''), path.resolve(currentDir, file));
        }
      }

      engine.compareBatch(baselines, currents)
        .then(results => {
          console.log(engine.generateReport(results));
        });
      break;
    }

    case 'report': {
      const resultsFile = args[1];
      if (!resultsFile) {
        console.error('请提供结果文件路径');
        return;
      }

      const resultsData = JSON.parse(fs.readFileSync(resultsFile, 'utf8'));
      const results = new Map(Object.entries(resultsData));
      console.log(engine.generateReport(results));
      break;
    }

    default:
      console.log('使用 --help 查看用法');
  }
}
