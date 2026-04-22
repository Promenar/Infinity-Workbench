/**
 * 配置加载模块
 */

import fs from 'fs';
import path from 'path';

export interface AgentTestConfig {
  jest: {
    preset?: string;
    configPath?: string;
    coverageThreshold?: {
      lines?: number;
      branches?: number;
      functions?: number;
      statements?: number;
    };
  };
  diagnosis: {
    maxProcessingTimeMs?: number;
    confidenceThreshold?: number;
    autoFixEnabled?: boolean;
  };
  visual: {
    baselineDir: string;
    snapshotDir: string;
    diffDir: string;
    threshold: number;
  };
  output: {
    resultsDir: string;
    reportsDir: string;
    logsDir: string;
  };
}

const DEFAULT_CONFIG: AgentTestConfig = {
  jest: {
    preset: 'react-native',
  },
  diagnosis: {
    maxProcessingTimeMs: 5000,
    confidenceThreshold: 0.7,
    autoFixEnabled: false,
  },
  visual: {
    baselineDir: '.agent-test/baseline',
    snapshotDir: '.agent-test/snapshots',
    diffDir: '.agent-test/diffs',
    threshold: 0.1,
  },
  output: {
    resultsDir: '.agent-test/results',
    reportsDir: '.agent-test/reports',
    logsDir: '.agent-test/logs',
  },
};

/**
 * 加载配置文件
 */
export function loadConfig(configPath?: string): AgentTestConfig {
  const searchPaths = configPath 
    ? [configPath]
    : [
        path.resolve(process.cwd(), 'agent-test.config.json'),
        path.resolve(process.cwd(), 'agent-test.config.ts'),
        path.resolve(__dirname, '../../agent-test.config.json'),
      ];

  for (const p of searchPaths) {
    if (fs.existsSync(p)) {
      try {
        const ext = path.extname(p);
        if (ext === '.json') {
          const content = JSON.parse(fs.readFileSync(p, 'utf8'));
          return mergeConfig(DEFAULT_CONFIG, content);
        } else if (ext === '.ts') {
          // 对于 .ts 文件，需要使用动态 import
          // 这里简化处理，返回默认配置
          console.warn(`TypeScript 配置文件暂不支持: ${p}`);
        }
      } catch (error) {
        console.warn(`加载配置文件失败: ${p}`, error);
      }
    }
  }

  return DEFAULT_CONFIG;
}

/**
 * 深度合并配置
 */
function mergeConfig(base: AgentTestConfig, override: Partial<AgentTestConfig>): AgentTestConfig {
  const result = { ...base };
  
  for (const key of Object.keys(override) as (keyof AgentTestConfig)[]) {
    const baseValue = base[key];
    const overrideValue = override[key];
    
    if (overrideValue && typeof overrideValue === 'object' && !Array.isArray(overrideValue)) {
      result[key] = { ...baseValue, ...overrideValue } as typeof baseValue;
    } else if (overrideValue !== undefined) {
      result[key] = overrideValue as typeof baseValue;
    }
  }
  
  return result;
}

/**
 * 获取默认配置
 */
export function getDefaultConfig(): AgentTestConfig {
  return { ...DEFAULT_CONFIG };
}
