/**
 * Baseline Manager - 视觉测试基线管理器
 * 
 * 职责：
 * 1. 管理截图基线
 * 2. 存储和检索基线
 * 3. 版本化基线
 * 4. 生成基线报告
 */

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

// ============================================================================
// Types
// ============================================================================

export interface BaselineEntry {
  id: string;
  screenName: string;
  variant: 'light' | 'dark';
  device: string;
  baselinePath: string;
  version: string;
  createdAt: string;
  hash: string;
  width: number;
  height: number;
}

export interface BaselineManifest {
  version: string;
  createdAt: string;
  updatedAt: string;
  deviceConfig: {
    model: string;
    os: string;
    scale: number;
  };
  entries: BaselineEntry[];
}

export interface BaselineSearchCriteria {
  screenName?: string;
  variant?: 'light' | 'dark';
  device?: string;
  version?: string;
}

// ============================================================================
// Baseline Manager
// ============================================================================

export class BaselineManager {
  private baselineDir: string;
  private manifestPath: string;
  private currentVersion: string;

  constructor(
    projectRoot: string = process.cwd(),
    version: string = 'v1.0.0'
  ) {
    this.projectRoot = projectRoot;
    this.currentVersion = version;
    this.baselineDir = path.resolve(projectRoot, `.agent/test-baselines/${version}`);
    this.manifestPath = path.resolve(this.baselineDir, 'manifest.json');
  }

  /**
   * 初始化基线目录
   */
  async initialize(): Promise<void> {
    if (!fs.existsSync(this.baselineDir)) {
      fs.mkdirSync(path.resolve(this.baselineDir, 'screens'), { recursive: true });
      fs.mkdirSync(path.resolve(this.baselineDir, 'components'), { recursive: true });
    }

    if (!fs.existsSync(this.manifestPath)) {
      await this.saveManifest(this.createEmptyManifest());
    }
  }

  /**
   * 添加新基线
   */
  async addBaseline(
    screenName: string,
    imagePath: string,
    variant: 'light' | 'dark',
    device: string,
    metadata: { width?: number; height?: number } = {}
  ): Promise<BaselineEntry> {
    await this.initialize();

    // 计算文件哈希
    const hash = await this.computeHash(imagePath);
    
    // 生成文件名
    const filename = `${screenName}.${variant}.${device.replace(/\s+/g, '-')}.png`;
    const destPath = path.resolve(this.baselineDir, 'screens', filename);

    // 复制文件
    fs.copyFileSync(imagePath, destPath);

    // 创建条目
    const entry: BaselineEntry = {
      id: crypto.randomUUID(),
      screenName,
      variant,
      device,
      baselinePath: destPath,
      version: this.currentVersion,
      createdAt: new Date().toISOString(),
      hash,
      width: metadata.width || 0,
      height: metadata.height || 0,
    };

    // 更新 manifest
    const manifest = await this.loadManifest();
    manifest.entries.push(entry);
    manifest.updatedAt = new Date().toISOString();
    await this.saveManifest(manifest);

    return entry;
  }

  /**
   * 查找基线
   */
  async findBaseline(criteria: BaselineSearchCriteria): Promise<BaselineEntry | null> {
    const manifest = await this.loadManifest();
    
    return manifest.entries.find(entry => {
      if (criteria.screenName && entry.screenName !== criteria.screenName) return false;
      if (criteria.variant && entry.variant !== criteria.variant) return false;
      if (criteria.device && entry.device !== criteria.device) return false;
      if (criteria.version && entry.version !== criteria.version) return false;
      return true;
    }) || null;
  }

  /**
   * 获取所有基线
   */
  async getAllBaselines(criteria?: BaselineSearchCriteria): Promise<BaselineEntry[]> {
    const manifest = await this.loadManifest();
    
    if (!criteria) {
      return manifest.entries;
    }

    return manifest.entries.filter(entry => {
      if (criteria.screenName && entry.screenName !== criteria.screenName) return false;
      if (criteria.variant && entry.variant !== criteria.variant) return false;
      if (criteria.device && entry.device !== criteria.device) return false;
      if (criteria.version && entry.version !== criteria.version) return false;
      return true;
    });
  }

  /**
   * 删除基线
   */
  async removeBaseline(entryId: string): Promise<boolean> {
    const manifest = await this.loadManifest();
    const entry = manifest.entries.find(e => e.id === entryId);
    
    if (!entry) return false;

    // 删除文件
    if (fs.existsSync(entry.baselinePath)) {
      fs.unlinkSync(entry.baselinePath);
    }

    // 从 manifest 移除
    manifest.entries = manifest.entries.filter(e => e.id !== entryId);
    manifest.updatedAt = new Date().toISOString();
    await this.saveManifest(manifest);

    return true;
  }

  /**
   * 更新基线（替换图片）
   */
  async updateBaseline(
    entryId: string,
    newImagePath: string
  ): Promise<BaselineEntry | null> {
    const manifest = await this.loadManifest();
    const entry = manifest.entries.find(e => e.id === entryId);
    
    if (!entry) return null;

    // 计算新哈希
    const newHash = await this.computeHash(newImagePath);

    // 替换文件
    fs.copyFileSync(newImagePath, entry.baselinePath);

    // 更新条目
    entry.hash = newHash;
    entry.createdAt = new Date().toISOString();
    
    manifest.updatedAt = new Date().toISOString();
    await this.saveManifest(manifest);

    return entry;
  }

  /**
   * 创建新版本基线
   */
  async createNewVersion(newVersion: string): Promise<BaselineManager> {
    const manager = new BaselineManager(this.projectRoot, newVersion);
    await manager.initialize();
    return manager;
  }

  /**
   * 获取基线统计
   */
  async getStats(): Promise<{
    total: number;
    byVariant: Record<string, number>;
    byDevice: Record<string, number>;
    byVersion: Record<string, number>;
  }> {
    const manifest = await this.loadManifest();
    
    const stats = {
      total: manifest.entries.length,
      byVariant: {} as Record<string, number>,
      byDevice: {} as Record<string, number>,
      byVersion: {} as Record<string, number>,
    };

    for (const entry of manifest.entries) {
      stats.byVariant[entry.variant] = (stats.byVariant[entry.variant] || 0) + 1;
      stats.byDevice[entry.device] = (stats.byDevice[entry.device] || 0) + 1;
      stats.byVersion[entry.version] = (stats.byVersion[entry.version] || 0) + 1;
    }

    return stats;
  }

  /**
   * 生成基线报告
   */
  async generateReport(): Promise<string> {
    const manifest = await this.loadManifest();
    const stats = await this.getStats();
    
    let report = `# 视觉测试基线报告\n\n`;
    report += `生成时间: ${new Date().toISOString()}\n`;
    report += `版本: ${this.currentVersion}\n\n`;
    
    report += `## 统计信息\n\n`;
    report += `| 指标 | 数值 |\n`;
    report += `|------|------|\n`;
    report += `| 总基线数 | ${stats.total} |\n`;
    
    report += `\n### 按变体\n\n`;
    for (const [variant, count] of Object.entries(stats.byVariant)) {
      report += `- ${variant}: ${count}\n`;
    }
    
    report += `\n### 按设备\n\n`;
    for (const [device, count] of Object.entries(stats.byDevice)) {
      report += `- ${device}: ${count}\n`;
    }
    
    report += `\n### 按版本\n\n`;
    for (const [version, count] of Object.entries(stats.byVersion)) {
      report += `- ${version}: ${count}\n`;
    }

    report += `\n## 基线详情\n\n`;
    report += `| 屏幕名称 | 变体 | 设备 | 版本 | 创建时间 |\n`;
    report += `|---------|------|------|------|----------|\n`;
    
    for (const entry of manifest.entries) {
      report += `| ${entry.screenName} | ${entry.variant} | ${entry.device} | ${entry.version} | ${entry.createdAt} |\n`;
    }

    return report;
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private createEmptyManifest(): BaselineManifest {
    return {
      version: this.currentVersion,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      deviceConfig: {
        model: 'iPhone 15 Pro',
        os: 'iOS 17.x',
        scale: 3,
      },
      entries: [],
    };
  }

  private async loadManifest(): Promise<BaselineManifest> {
    if (!fs.existsSync(this.manifestPath)) {
      return this.createEmptyManifest();
    }
    return JSON.parse(fs.readFileSync(this.manifestPath, 'utf8'));
  }

  private async saveManifest(manifest: BaselineManifest): Promise<void> {
    fs.writeFileSync(this.manifestPath, JSON.stringify(manifest, null, 2), 'utf8');
  }

  private async computeHash(filePath: string): Promise<string> {
    const buffer = fs.readFileSync(filePath);
    return crypto.createHash('sha256').update(buffer).digest('hex').slice(0, 16);
  }
}

// ============================================================================
// CLI Entry Point
// ============================================================================

export function main(): void {
  const args = process.argv.slice(2);
  
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`
Baseline Manager - 视觉测试基线管理

用法:
  npx ts-node baseline-manager.ts add <screen-name> <image-path> [options]
  npx ts-node baseline-manager.ts list [criteria]
  npx ts-node baseline-manager.ts remove <entry-id>
  npx ts-node baseline-manager.ts report
  npx ts-node baseline-manager.ts stats

选项:
  --variant <light|dark>    指定变体 (默认: light)
  --device <device-name>    指定设备 (默认: iPhone 15 Pro)
  --version <version>        指定版本 (默认: v1.0.0)
  --help                     显示帮助
`);
    return;
  }

  const command = args[0];
  const manager = new BaselineManager();

  switch (command) {
    case 'add': {
      const screenName = args[1];
      const imagePath = args[2];
      
      if (!screenName || !imagePath) {
        console.error('请提供屏幕名称和图片路径');
        return;
      }

      const variant = args.includes('--variant')
        ? args[args.indexOf('--variant') + 1] as 'light' | 'dark'
        : 'light';
      const device = args.includes('--device')
        ? args[args.indexOf('--device') + 1]
        : 'iPhone 15 Pro';

      manager.addBaseline(screenName, imagePath, variant, device)
        .then(entry => {
          console.log(`✅ 基线已添加: ${entry.id}`);
        })
        .catch(e => {
          console.error(`❌ 添加失败: ${e.message}`);
        });
      break;
    }

    case 'list': {
      manager.getAllBaselines()
        .then(entries => {
          if (entries.length === 0) {
            console.log('暂无基线');
            return;
          }
          
          entries.forEach(entry => {
            console.log(`${entry.id} | ${entry.screenName} | ${entry.variant} | ${entry.device}`);
          });
        });
      break;
    }

    case 'remove': {
      const entryId = args[1];
      if (!entryId) {
        console.error('请提供条目 ID');
        return;
      }

      manager.removeBaseline(entryId)
        .then(success => {
          console.log(success ? '✅ 已删除' : '❌ 未找到条目');
        });
      break;
    }

    case 'report': {
      manager.generateReport()
        .then(report => {
          console.log(report);
        });
      break;
    }

    case 'stats': {
      manager.getStats()
        .then(stats => {
          console.log('基线统计:');
          console.log(`  总数: ${stats.total}`);
          console.log('  按变体:', stats.byVariant);
          console.log('  按设备:', stats.byDevice);
          console.log('  按版本:', stats.byVersion);
        });
      break;
    }

    default:
      console.log('使用 --help 查看用法');
  }
}
