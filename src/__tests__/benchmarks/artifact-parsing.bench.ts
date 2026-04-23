/**
 * Artifact 解析性能基准测试
 *
 * 测量 parseEChartsContent 等函数在大量输入下的解析吞吐量。
 * jsonrepair 为纯 JS 库，无需 mock 原生模块。
 */

import { parseEChartsContent, extractEChartsMetadata } from '../../lib/artifact-parser';

/**
 * 生成模拟 ECharts JSON 配置
 */
function generateEChartsJson(index: number): string {
  return JSON.stringify({
    title: { text: `Chart-${index}` },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: ['A', 'B', 'C', 'D', 'E'] },
    yAxis: { type: 'value' },
    series: [
      {
        type: 'bar',
        data: [Math.random() * 100, Math.random() * 100, Math.random() * 100],
      },
    ],
  });
}

/**
 * 生成带代码块标记的 ECharts 内容
 */
function generateEChartsCodeBlock(index: number): string {
  return '```echarts\n' + generateEChartsJson(index) + '\n```';
}

describe('Artifact 解析性能基准测试', () => {
  it('应在 100ms 内解析 500 个 ECharts JSON 对象', () => {
    const inputs: string[] = [];
    for (let i = 0; i < 500; i++) {
      inputs.push(generateEChartsJson(i));
    }

    const start = performance.now();

    for (const input of inputs) {
      parseEChartsContent(input);
    }

    const duration = performance.now() - start;
    console.log(`parseEChartsContent 解析 500 个 JSON: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(100);
  });

  it('应在 200ms 内解析 500 个带代码块标记的 ECharts 内容', () => {
    const inputs: string[] = [];
    for (let i = 0; i < 500; i++) {
      inputs.push(generateEChartsCodeBlock(i));
    }

    const start = performance.now();

    for (const input of inputs) {
      parseEChartsContent(input);
    }

    const duration = performance.now() - start;
    console.log(`parseEChartsContent 解析 500 个代码块: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(200);
  });

  it('应在 100ms 内提取 500 个 ECharts 元数据', () => {
    const options: any[] = [];
    for (let i = 0; i < 500; i++) {
      options.push({
        title: { text: `Title-${i}` },
        series: [{ type: 'line' }, { type: 'bar' }],
      });
    }

    const start = performance.now();

    for (const option of options) {
      extractEChartsMetadata(option);
    }

    const duration = performance.now() - start;
    console.log(`extractEChartsMetadata 提取 500 个: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(100);
  });

  it('应在 150ms 内解析包含 BOM 和注释的 ECharts 内容', () => {
    const inputs: string[] = [];
    for (let i = 0; i < 200; i++) {
      // 带 BOM 头和尾部逗号的不规范 JSON
      const json = `{\uFEFF"title":{"text":"Chart-${i}"},// comment\n"series":[{"type":"bar","data":[1,2,3,]},}`;
      inputs.push(json);
    }

    const start = performance.now();

    for (const input of inputs) {
      parseEChartsContent(input);
    }

    const duration = performance.now() - start;
    console.log(`parseEChartsContent 解析 200 个不规范 JSON: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(150);
  });
});
