/**
 * Agent Test CLI 入口
 * 
 * 使用方式：
 *   npx tsx scripts/agent-test/cli.ts
 *   npx tsx scripts/agent-test/cli.ts --mode=run --scope=src/lib
 *   npx tsx scripts/agent-test/cli.ts --mode=fix --scope=src/lib
 */

import path from 'path';
import fs from 'fs';
import { Logger } from './utils/logger.js';
import { JestRunner } from './runner/jest-runner.js';
import { parseJestJsonReport, generateReportId } from './parser/jest-parser.js';
import { BenchmarkRunner } from './runner/benchmark-runner.js';
import { ErrorClassifier } from './diagnostician/error-classifier.js';
import { StackParser } from './diagnostician/stack-parser.js';
import { SafeModifier } from './fix/safe-modifier.js';
import { RollbackManager } from './fix/rollback-manager.js';
import { ScreenshotManager } from './visual/screenshot-manager.js';
import { BaselineManager } from './visual/baseline-manager.js';
import { DiffEngine } from './visual/diff-engine.js';

// CLI 参数接口
interface CliArgs {
  mode: 'run' | 'fix' | 'diagnose' | 'benchmark' | 'visual';
  scope?: string;
  testNamePattern?: string;
  coverage: boolean;
  noFix: boolean;
  verbose: boolean;
  updateSnapshot: boolean;
  help: boolean;
  visualCapture: boolean;
  visualCompare: boolean;
  visualScreen: string;
}

// 解析命令行参数
function parseArgs(): CliArgs {
  const args = process.argv.slice(2);
  const result: CliArgs = {
    mode: 'run',
    coverage: false,
    noFix: false,
    verbose: false,
    updateSnapshot: false,
    help: false,
    visualCapture: false,
    visualCompare: false,
    visualScreen: '',
  };

  for (const arg of args) {
    if (arg === '--help' || arg === '-h') {
      result.help = true;
      break;
    }

    if (arg.startsWith('--mode=')) {
      result.mode = arg.split('=')[1] as CliArgs['mode'];
    } else if (arg.startsWith('--scope=')) {
      result.scope = arg.split('=')[1];
    } else if (arg.startsWith('--testNamePattern=')) {
      result.testNamePattern = arg.split('=')[1];
    } else if (arg === '--coverage') {
      result.coverage = true;
    } else if (arg === '--no-fix') {
      result.noFix = true;
    } else if (arg === '--verbose' || arg === '-v') {
      result.verbose = true;
    } else if (arg === '--updateSnapshot' || arg === '-u') {
      result.updateSnapshot = true;
    } else if (arg === '--fix') {
      result.mode = 'fix';
    } else if (arg === '--diagnose') {
      result.mode = 'diagnose';
    } else if (arg === '--benchmark') {
      result.mode = 'benchmark';
    } else if (arg === '--visual') {
      result.mode = 'visual';
    } else if (arg === '--visual-capture') {
      result.visualCapture = true;
    } else if (arg === '--visual-compare') {
      result.visualCompare = true;
    } else if (arg.startsWith('--visual-screen=')) {
      result.visualScreen = arg.split('=')[1];
    }
  }

  return result;
}

// 打印帮助信息
function printHelp(): void {
  const helpText = `
${Logger.name ? '' : '\x1b[1m'}Agent Test CLI\x1b[0m

用法:
  agent-test [选项]

选项:
  --mode=<mode>         运行模式: run | fix | diagnose | benchmark | visual
                        (默认: run)
  --scope=<pattern>     测试范围 (文件路径或 glob 模式)
  --testNamePattern=<p> 测试名称匹配模式
  --coverage            生成覆盖率报告
  --no-fix              禁用自动修复 (仅诊断模式)
  --updateSnapshot      更新快照
  --verbose, -v         详细输出
  --help, -h            显示帮助信息

视觉测试选项 (--mode=visual):
  --visual-capture      截屏并创建/更新基线
  --visual-compare      对比当前截图与基线
  --visual-screen=<name> 指定屏幕名称 (默认: default)

示例:
  agent-test --mode=run --scope=src/lib
  agent-test --coverage
  agent-test --verbose --scope=src/components
  `;
  console.log(helpText);
}

// 主运行模式
async function runMode(args: CliArgs, logger: Logger): Promise<number> {
  const reportId = generateReportId();
  const jsonOutput = path.resolve(
    process.cwd(),
    `.agent-test/results/${reportId}.json`
  );

  // 确保输出目录存在
  const outputDir = path.dirname(jsonOutput);
  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  logger.section('执行测试');
  logger.info(`报告 ID: ${reportId}`);
  logger.info(`测试范围: ${args.scope || '全部测试'}`);

  const runner = new JestRunner({ 
    verbose: args.verbose,
    rootDir: process.cwd(),
  });

  // 检查 Jest 是否可用
  if (!JestRunner.checkAvailable()) {
    logger.error('Jest 不可用，请确保已安装 jest');
    return 1;
  }

  const jestVersion = JestRunner.getVersion();
  if (jestVersion) {
    logger.debug(`Jest 版本: ${jestVersion}`);
  }

  const result = await runner.run({
    scope: args.scope,
    testNamePattern: args.testNamePattern,
    coverage: args.coverage,
    jsonOutput,
    updateSnapshot: args.updateSnapshot,
  });

  logger.info(`测试执行耗时: ${result.duration}ms`);
  logger.info(`退出码: ${result.exitCode}`);

  if (result.success) {
    logger.success('所有测试通过');
  } else {
    logger.error('测试失败');
  }

  // 解析报告
  if (fs.existsSync(jsonOutput)) {
    try {
      const report = parseJestJsonReport(jsonOutput);
      
      logger.section('测试摘要');
      logger.info(`总测试数: ${report.summary.totalTests}`);
      logger.info(`通过: ${report.summary.passed} (${report.summary.passRate.toFixed(1)}%)`);
      
      if (report.summary.failed > 0) {
        logger.error(`失败: ${report.summary.failed}`);
      }
      if (report.summary.skipped > 0) {
        logger.warn(`跳过: ${report.summary.skipped}`);
      }

      if (report.coverage) {
        logger.section('覆盖率');
        logger.info(`语句: ${report.coverage.statements.pct.toFixed(1)}%`);
        logger.info(`分支: ${report.coverage.branches.pct.toFixed(1)}%`);
        logger.info(`函数: ${report.coverage.functions.pct.toFixed(1)}%`);
        logger.info(`行: ${report.coverage.lines.pct.toFixed(1)}%`);
      }
    } catch (error) {
      logger.warn(`解析报告失败: ${error}`);
    }
  }

  return result.success ? 0 : 1;
}

// 诊断模式
async function diagnoseMode(args: CliArgs, logger: Logger): Promise<number> {
  logger.section('诊断模式');
  
  // 从最近的测试报告中读取失败信息
  const resultsDir = path.resolve(process.cwd(), '.agent-test/results');
  const latestReport = findLatestReport(resultsDir);
  
  if (!latestReport) {
    logger.warn('未找到测试报告，请先运行测试: agent-test --mode=run');
    return 1;
  }
  
  try {
    const report = parseJestJsonReport(latestReport);
    
    if (report.failedTests.length === 0) {
      logger.success('没有失败的测试');
      return 0;
    }
    
    const classifier = new ErrorClassifier();
    const stackParser = new StackParser();
    
    logger.info(`分析 ${report.failedTests.length} 个失败测试...`);
    
    let classifiedCount = 0;
    for (const failed of report.failedTests) {
      if (failed.error) {
        const classified = classifier.classify(failed.error);
        
        logger.section(`测试: ${failed.testName}`);
        logger.info(`类别: ${classified.category}`);
        logger.info(`严重程度: ${classified.severity}`);
        logger.info(`置信度: ${(classified.confidence * 100).toFixed(0)}%`);
        logger.info(`匹配模式: ${classified.patternName}`);
        
        if (failed.error.stack) {
          const parsed = stackParser.parse(failed.error.stack);
          if (parsed.rootCause) {
            logger.info(`根因位置: ${parsed.rootCause.file}:${parsed.rootCause.line}`);
            
            // 获取代码上下文
            const context = stackParser.getContext(
              parsed.rootCause.file,
              parsed.rootCause.line
            );
            logger.debug('代码上下文:');
            context.forEach(line => logger.debug(`  ${line}`));
          }
        }
        
        if (classified.suggestedFix) {
          logger.section('建议修复');
          console.log(classified.suggestedFix);
        }
        
        classifiedCount++;
      }
    }
    
    logger.success(`已完成 ${classifiedCount} 个测试的诊断`);
    return 0;
  } catch (error) {
    logger.error(`诊断失败: ${error}`);
    return 1;
  }
}

function findLatestReport(dir: string): string | null {
  if (!fs.existsSync(dir)) return null;
  
  const files = fs.readdirSync(dir)
    .filter(f => f.endsWith('.json'))
    .map(f => ({
      name: f,
      time: fs.statSync(path.resolve(dir, f)).mtimeMs,
    }))
    .sort((a, b) => b.time - a.time);
  
  return files.length > 0 ? path.resolve(dir, files[0].name) : null;
}

// 修复模式
async function fixMode(args: CliArgs, logger: Logger): Promise<number> {
  logger.section('修复模式');
  
  if (args.noFix) {
    logger.warn('--no-fix 已启用，仅进行诊断');
    return diagnoseMode(args, logger);
  }
  
  // 从最近的测试报告中读取失败信息
  const resultsDir = path.resolve(process.cwd(), '.agent-test/results');
  const latestReport = findLatestReport(resultsDir);
  
  if (!latestReport) {
    logger.warn('未找到测试报告，请先运行测试: agent-test --mode=run');
    return 1;
  }
  
  try {
    const report = parseJestJsonReport(latestReport);
    
    if (report.failedTests.length === 0) {
      logger.success('没有需要修复的测试');
      return 0;
    }
    
    const classifier = new ErrorClassifier();
    const stackParser = new StackParser();
    const modifier = new SafeModifier();
    const rollbackManager = new RollbackManager();
    
    logger.info(`尝试修复 ${report.failedTests.length} 个失败测试...`);
    
    let fixedCount = 0;
    let failedFixCount = 0;
    const rollbackIds: string[] = [];
    
    for (const failed of report.failedTests) {
      if (!failed.error) continue;
      
      const classified = classifier.classify(failed.error);
      
      if (classified.confidence < 0.5) {
        logger.warn(`测试 "${failed.testName}" 置信度过低，跳过`);
        failedFixCount++;
        continue;
      }
      
      if (failed.error.stack) {
        const parsed = stackParser.parse(failed.error.stack);
        
        if (parsed.rootCause) {
          logger.section(`修复: ${failed.testName}`);
          
          // 读取当前文件内容
          const filePath = parsed.rootCause.file;
          if (fs.existsSync(filePath)) {
            const content = fs.readFileSync(filePath, 'utf8');
            const lines = content.split('\n');
            const targetLine = lines[parsed.rootCause.line - 1];
            
            if (targetLine) {
              // 应用简单的修复（添加可选链）
              let fixedLine = targetLine;
              
              if (classified.category === 'type_error' && 
                  classified.patternName === '访问 undefined 属性') {
                // 检测并修复 undefined 访问
                fixedLine = targetLine.replace(
                  /(\w+)\.(\w+)(?!\?)(?![=(])/g,
                  '$1?.$2'
                );
                
                if (fixedLine !== targetLine) {
                  lines[parsed.rootCause.line - 1] = fixedLine;
                  const newContent = lines.join('\n');
                  
                  // 记录修改
                  const change = {
                    filePath,
                    backupPath: path.resolve(process.cwd(), '.agent-test/backups', 
                      `${path.basename(filePath)}.${Date.now()}.bak`),
                  };
                  
                  // 备份
                  fs.mkdirSync(path.dirname(change.backupPath), { recursive: true });
                  fs.writeFileSync(change.backupPath, content);
                  
                  // 应用修改
                  fs.writeFileSync(filePath, newContent);
                  
                  rollbackIds.push(rollbackManager.recordChange(change, 
                    `Fix: ${failed.testName}`));
                  
                  logger.success(`已修复: ${filePath}:${parsed.rootCause.line}`);
                  logger.debug(`  ${targetLine.trim()}`);
                  logger.debug(`→ ${fixedLine.trim()}`);
                  
                  fixedCount++;
                }
              }
            }
          }
        }
      }
    }
    
    logger.section('修复总结');
    logger.success(`成功修复: ${fixedCount}`);
    logger.warn(`跳过: ${failedFixCount}`);
    
    if (rollbackIds.length > 0) {
      logger.info(`可使用以下命令回滚: agent-test --rollback=${rollbackIds[0]}`);
    }
    
    return fixedCount > 0 ? 0 : 1;
  } catch (error) {
    logger.error(`修复失败: ${error}`);
    return 1;
  }
}

// 基准测试模式
async function benchmarkMode(args: CliArgs, logger: Logger): Promise<number> {
  logger.section('基准测试模式');
  
  const runner = new BenchmarkRunner(process.cwd(), args.verbose);
  
  // 检查是否指定了特定测试
  const specificTest = args.scope;
  
  if (specificTest) {
    const config = runner.listConfigs().find(c => c.name === specificTest);
    if (config) {
      try {
        const result = await runner.run(config);
        
        if (result.regression) {
          logger.error(`⚠️  性能退化检测: ${result.degradationPercent?.toFixed(1)}%`);
          return 1;
        }
        
        logger.success('基准测试通过');
        return 0;
      } catch (error) {
        logger.error(`基准测试失败: ${error}`);
        return 1;
      }
    } else {
      logger.error(`未找到基准测试: ${specificTest}`);
      logger.info('可用的基准测试:');
      runner.listConfigs().forEach(c => {
        logger.info(`  - ${c.name}: ${c.description}`);
      });
      return 1;
    }
  }
  
  // 运行所有基准测试
  try {
    const results = await runner.runAll();
    
    const regressions = results.filter(r => r.regression).length;
    
    logger.section('基准测试总结');
    logger.info(`通过: ${results.length - regressions}/${results.length}`);
    
    if (regressions > 0) {
      logger.error(`性能退化: ${regressions}`);
      return 1;
    }
    
    logger.success('所有基准测试通过');
    return 0;
  } catch (error) {
    logger.error(`基准测试执行失败: ${error}`);
    return 1;
  }
}

// 视觉测试模式
async function visualMode(args: CliArgs, logger: Logger): Promise<number> {
  logger.section('视觉测试模式');

  const projectRoot = process.cwd();
  const screenName = args.visualScreen || 'default';

  // 1. 初始化三个模块
  const screenshotManager = new ScreenshotManager(
    path.resolve(projectRoot, '.agent-test/screenshots'),
    projectRoot,
  );
  const baselineManager = new BaselineManager(projectRoot);
  const diffEngine = new DiffEngine({
    outputDir: path.resolve(projectRoot, '.agent-test/visual-diffs'),
  });

  await baselineManager.initialize();

  // 2. 检查平台
  if (process.platform !== 'darwin') {
    logger.error('视觉测试当前仅支持 macOS（需要 iOS Simulator）');
    return 1;
  }

  // 3. 列出可用模拟器
  const iosDevices = screenshotManager.listIOSDevices();
  if (iosDevices.length === 0) {
    logger.error('未找到可用的 iOS 模拟器，请先启动模拟器');
    return 1;
  }
  logger.info(`可用模拟器: ${iosDevices.map(d => d.name).join(', ')}`);

  // 4. 截图模式 (--visual-capture)
  if (args.visualCapture) {
    logger.info(`正在截取屏幕: ${screenName} ...`);

    const captureResult = await screenshotManager.capture(screenName, { platform: 'ios' });

    if (!captureResult.success) {
      logger.error(`截图失败: ${captureResult.error}`);
      return 1;
    }

    logger.success(`截图成功: ${captureResult.path}`);
    logger.info(`设备: ${captureResult.device}`);

    // 将截图保存为基线
    const entry = await baselineManager.addBaseline(
      screenName,
      captureResult.path!,
      'light',
      captureResult.device || 'iPhone 15 Pro',
    );

    logger.success(`基线已创建/更新: ${entry.id}`);
    logger.info(`  屏幕名称: ${entry.screenName}`);
    logger.info(`  设备: ${entry.device}`);
    logger.info(`  版本: ${entry.version}`);
    return 0;
  }

  // 5. 对比模式 (--visual-compare)
  if (args.visualCompare) {
    // 先截取当前屏幕
    logger.info(`正在截取当前屏幕: ${screenName} ...`);

    const captureResult = await screenshotManager.capture(screenName, { platform: 'ios' });

    if (!captureResult.success) {
      logger.error(`截图失败: ${captureResult.error}`);
      return 1;
    }

    logger.success(`截图成功: ${captureResult.path}`);

    // 查找对应基线
    const baseline = await baselineManager.findBaseline({
      screenName,
      variant: 'light',
    });

    if (!baseline) {
      logger.warn(`未找到屏幕 "${screenName}" 的基线`);
      logger.info('请先使用 --visual-capture 创建基线');
      return 1;
    }

    logger.info(`找到基线: ${baseline.baselinePath}`);
    logger.info(`  创建时间: ${baseline.createdAt}`);

    // 执行对比
    logger.info('正在执行像素级对比 ...');
    const diffResult = await diffEngine.compare(
      baseline.baselinePath,
      captureResult.path!,
      screenName,
    );

    logger.section('对比结果');
    logger.info(`状态: ${diffResult.status === 'pass' ? '✅ 通过' : diffResult.status === 'regression' ? '❌ 回归' : '🆕 新增'}`);
    logger.info(`差异百分比: ${(diffResult.diffPercentage * 100).toFixed(2)}%`);
    logger.info(`差异像素数: ${diffResult.diffPixelCount} / ${diffResult.totalPixels}`);

    if (diffResult.analysis) {
      logger.info(`分析: ${diffResult.analysis}`);
    }

    if (diffResult.diffPath) {
      logger.info(`差异图像: ${diffResult.diffPath}`);
    }

    if (diffResult.status === 'regression') {
      logger.error(`检测到视觉回归，差异 ${(diffResult.diffPercentage * 100).toFixed(1)}% 超过阈值`);
      return 1;
    }

    logger.success('视觉测试通过');
    return 0;
  }

  // 6. 默认模式：同时执行截屏和对比
  logger.info('执行完整的视觉测试流程 (截屏 + 对比) ...');

  // 截取当前屏幕
  const captureResult = await screenshotManager.capture(screenName, { platform: 'ios' });

  if (!captureResult.success) {
    logger.error(`截图失败: ${captureResult.error}`);
    return 1;
  }

  logger.success(`截图成功: ${captureResult.path}`);

  // 查找基线
  const baseline = await baselineManager.findBaseline({
    screenName,
    variant: 'light',
  });

  if (!baseline) {
    logger.warn(`未找到屏幕 "${screenName}" 的基线`);
    logger.info('提示: 使用 --visual-capture 先创建基线');
    logger.info('本次截图已保存，可作为首次基线参考');
    return 0;
  }

  // 对比
  const diffResult = await diffEngine.compare(
    baseline.baselinePath,
    captureResult.path!,
    screenName,
  );

  logger.section('对比结果');
  logger.info(`状态: ${diffResult.status === 'pass' ? '✅ 通过' : diffResult.status === 'regression' ? '❌ 回归' : '🆕 新增'}`);
  logger.info(`差异百分比: ${(diffResult.diffPercentage * 100).toFixed(2)}%`);
  logger.info(`差异像素数: ${diffResult.diffPixelCount} / ${diffResult.totalPixels}`);

  if (diffResult.analysis) {
    logger.info(`分析: ${diffResult.analysis}`);
  }

  if (diffResult.diffPath) {
    logger.info(`差异图像: ${diffResult.diffPath}`);
  }

  if (diffResult.status === 'regression') {
    logger.error(`检测到视觉回归`);
    return 1;
  }

  logger.success('视觉测试通过');
  return 0;
}

// 主入口
async function main(): Promise<void> {
  const args = parseArgs();
  const logger = new Logger(args.verbose);

  if (args.help) {
    printHelp();
    process.exit(0);
  }

  logger.section('Agent Test CLI');
  logger.info(`模式: ${args.mode}`);

  let exitCode = 0;

  try {
    switch (args.mode) {
      case 'run':
        exitCode = await runMode(args, logger);
        break;
      case 'diagnose':
        exitCode = await diagnoseMode(args, logger);
        break;
      case 'fix':
        exitCode = await fixMode(args, logger);
        break;
      case 'benchmark':
        exitCode = await benchmarkMode(args, logger);
        break;
      case 'visual':
        exitCode = await visualMode(args, logger);
        break;
      default:
        logger.error(`未知模式: ${args.mode}`);
        exitCode = 1;
    }
  } catch (error) {
    logger.error(`执行失败: ${error}`);
    exitCode = 1;
  }

  process.exit(exitCode);
}

main().catch(console.error);
