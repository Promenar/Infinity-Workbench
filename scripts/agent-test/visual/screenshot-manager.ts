/**
 * Screenshot Manager - 模拟器截图管理器
 * 
 * 职责：
 * 1. 在 iOS/Android 模拟器上截图
 * 2. 管理截图文件
 * 3. 提供跨平台截图支持
 */

import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

// ============================================================================
// Types
// ============================================================================

export interface SimulatorDevice {
  name: string;
  id: string;
  udid: string;
  width: number;
  height: number;
  scale: number;
  platform: 'ios' | 'android';
}

export interface ScreenshotResult {
  success: boolean;
  path?: string;
  device?: string;
  timestamp: string;
  error?: string;
}

// ============================================================================
// Available Devices
// ============================================================================

export const IOS_DEVICES: SimulatorDevice[] = [
  {
    name: 'iPhone 15 Pro',
    id: 'iPhone15Pro',
    udid: 'iPhone15Pro',
    width: 1179,
    height: 2556,
    scale: 3,
    platform: 'ios',
  },
  {
    name: 'iPhone 15',
    id: 'iPhone15',
    udid: 'iPhone15',
    width: 1170,
    height: 2532,
    scale: 3,
    platform: 'ios',
  },
  {
    name: 'iPhone 14 Pro',
    id: 'iPhone14Pro',
    udid: 'iPhone14Pro',
    width: 1179,
    height: 2556,
    scale: 3,
    platform: 'ios',
  },
];

export const ANDROID_DEVICES: SimulatorDevice[] = [
  {
    name: 'Pixel 7',
    id: 'pixel_7',
    udid: 'emulator-5554',
    width: 1080,
    height: 2400,
    scale: 2,
    platform: 'android',
  },
  {
    name: 'Pixel 6',
    id: 'pixel_6',
    udid: 'emulator-5556',
    width: 1080,
    height: 2400,
    scale: 2,
    platform: 'android',
  },
];

// ============================================================================
// Screenshot Manager
// ============================================================================

export class ScreenshotManager {
  private outputDir: string;
  private projectRoot: string;

  constructor(outputDir: string = '/tmp/screenshots', projectRoot: string = process.cwd()) {
    this.outputDir = outputDir;
    this.projectRoot = projectRoot;
    this.ensureOutputDir();
  }

  /**
   * 在 iOS 模拟器上截图
   */
  async captureIOS(screenName: string, deviceId?: string): Promise<ScreenshotResult> {
    const device = this.findIOSDevice(deviceId);
    
    if (!device) {
      return {
        success: false,
        timestamp: new Date().toISOString(),
        error: `未找到 iOS 模拟器: ${deviceId}`,
      };
    }

    // 确保模拟器已启动
    try {
      execSync(`xcrun simctl boot "${device.udid}"`, { stdio: 'pipe' });
    } catch {
      // 模拟器可能已经在运行
    }

    const filename = `${screenName}-${Date.now()}.png`;
    const outputPath = path.resolve(this.outputDir, filename);

    try {
      execSync(
        `xcrun simctl io "${device.udid}" screenshot "${outputPath}"`,
        { stdio: 'pipe' }
      );

      return {
        success: true,
        path: outputPath,
        device: device.name,
        timestamp: new Date().toISOString(),
      };
    } catch (e: any) {
      return {
        success: false,
        device: device.name,
        timestamp: new Date().toISOString(),
        error: e.message,
      };
    }
  }

  /**
   * 在 Android 模拟器上截图
   */
  async captureAndroid(screenName: string, deviceId?: string): Promise<ScreenshotResult> {
    const device = deviceId || 'emulator-5554';

    const filename = `${screenName}-${Date.now()}.png`;
    const localPath = path.resolve(this.outputDir, filename);

    try {
      // 使用 adb 截图
      execSync(`adb -s ${device} shell screencap -p /sdcard/screen.png`, { stdio: 'pipe' });
      execSync(`adb -s ${device} pull /sdcard/screen.png "${localPath}"`, { stdio: 'pipe' });
      execSync(`adb -s ${device} shell rm /sdcard/screen.png`, { stdio: 'pipe' });

      return {
        success: true,
        path: localPath,
        device,
        timestamp: new Date().toISOString(),
      };
    } catch (e: any) {
      return {
        success: false,
        device,
        timestamp: new Date().toISOString(),
        error: e.message,
      };
    }
  }

  /**
   * 截图（自动选择平台）
   */
  async capture(
    screenName: string,
    options: { platform?: 'ios' | 'android'; deviceId?: string } = {}
  ): Promise<ScreenshotResult> {
    const { platform = 'ios', deviceId } = options;

    if (platform === 'android') {
      return this.captureAndroid(screenName, deviceId);
    }
    return this.captureIOS(screenName, deviceId);
  }

  /**
   * 列出可用的 iOS 模拟器
   */
  listIOSDevices(): SimulatorDevice[] {
    try {
      const output = execSync('xcrun simctl list devices available', {
        encoding: 'utf8',
      });

      const devices: SimulatorDevice[] = [...IOS_DEVICES];
      
      // 尝试从输出中提取额外的设备
      const lines = output.split('\n');
      for (const line of lines) {
        const match = line.match(/iPhone\s+(\d+\s+\w+)\s+\(([^)]+)\)/);
        if (match) {
          const existing = devices.find(d => d.name.includes(match[1]));
          if (!existing) {
            devices.push({
              name: match[1],
              id: match[1].replace(/\s+/g, ''),
              udid: match[2],
              width: 1170,
              height: 2532,
              scale: 3,
              platform: 'ios',
            });
          }
        }
      }

      return devices;
    } catch {
      return IOS_DEVICES;
    }
  }

  /**
   * 列出可用的 Android 模拟器
   */
  listAndroidDevices(): SimulatorDevice[] {
    try {
      const output = execSync('adb devices', { encoding: 'utf8' });
      const lines = output.split('\n').filter(l => l.trim());
      
      const devices: SimulatorDevice[] = [];
      
      for (const line of lines.slice(1)) {
        const [udid, status] = line.split('\t');
        if (status === 'device') {
          devices.push({
            name: `Android Device ${udid}`,
            id: udid,
            udid,
            width: 1080,
            height: 2400,
            scale: 2,
            platform: 'android',
          });
        }
      }

      return devices.length > 0 ? devices : ANDROID_DEVICES;
    } catch {
      return ANDROID_DEVICES;
    }
  }

  /**
   * 清理旧截图
   */
  cleanup(days: number = 7): number {
    if (!fs.existsSync(this.outputDir)) {
      return 0;
    }

    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    const files = fs.readdirSync(this.outputDir);
    let deleted = 0;

    for (const file of files) {
      if (!file.endsWith('.png')) continue;
      
      const fullPath = path.resolve(this.outputDir, file);
      const stat = fs.statSync(fullPath);
      
      if (stat.mtimeMs < cutoff) {
        fs.unlinkSync(fullPath);
        deleted++;
      }
    }

    return deleted;
  }

  // -------------------------------------------------------------------------
  // Private Methods
  // -------------------------------------------------------------------------

  private ensureOutputDir(): void {
    if (!fs.existsSync(this.outputDir)) {
      fs.mkdirSync(this.outputDir, { recursive: true });
    }
  }

  private findIOSDevice(deviceId?: string): SimulatorDevice | undefined {
    const devices = this.listIOSDevices();
    
    if (deviceId) {
      return devices.find(d => 
        d.id === deviceId || 
        d.name === deviceId || 
        d.udid === deviceId
      );
    }

    // 返回默认设备
    return devices[0];
  }
}

// ============================================================================
// CLI Entry Point
// ============================================================================

export function main(): void {
  const args = process.argv.slice(2);
  const manager = new ScreenshotManager();
  
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`
Screenshot Manager - 模拟器截图工具

用法:
  npx ts-node screenshot-manager.ts <screen-name> [options]
  npx ts-node screenshot-manager.ts --list-devices
  npx ts-node screenshot-manager.ts --cleanup [days]

选项:
  --platform <ios|android>   指定平台 (默认: ios)
  --device <device-id>       指定设备 ID
  --list-devices             列出可用设备
  --cleanup [days]           清理旧截图 (默认 7 天)
  --help                     显示帮助
`);
    return;
  }

  if (args.includes('--list-devices')) {
    console.log('iOS 模拟器:');
    manager.listIOSDevices().forEach(d => {
      console.log(`  - ${d.name} (${d.udid})`);
    });
    
    console.log('\nAndroid 模拟器:');
    manager.listAndroidDevices().forEach(d => {
      console.log(`  - ${d.name} (${d.udid})`);
    });
    return;
  }

  if (args.includes('--cleanup')) {
    const days = parseInt(args[args.indexOf('--cleanup') + 1] || '7', 10);
    const deleted = manager.cleanup(days);
    console.log(`已清理 ${deleted} 个旧截图`);
    return;
  }

  // 截图
  const screenName = args[0];
  if (!screenName) {
    console.log('请指定截图名称');
    console.log('使用 --help 查看用法');
    return;
  }

  const platform = args.includes('--platform') 
    ? args[args.indexOf('--platform') + 1] as 'ios' | 'android'
    : 'ios';
  
  const deviceId = args.includes('--device')
    ? args[args.indexOf('--device') + 1]
    : undefined;

  console.log(`正在截图: ${screenName} (${platform})...`);
  
  manager.capture(screenName, { platform, deviceId })
    .then(result => {
      if (result.success) {
        console.log(`✅ 截图成功: ${result.path}`);
      } else {
        console.error(`❌ 截图失败: ${result.error}`);
        process.exit(1);
      }
    })
    .catch(e => {
      console.error(`❌ 错误: ${e.message}`);
      process.exit(1);
    });
}
