/**
 * StreamParser 性能基准测试
 *
 * 测量 StreamParser 在大量 SSE chunk 输入下的解析吞吐量。
 * 所有 React Native 原生模块均已 mock，确保纯 JS 环境。
 */

// Mock 原生模块及外部依赖
jest.mock('../../types/skills', () => ({
  ToolCall: {},
}));

jest.mock('../../lib/llm/response-normalizer', () => ({
  ProviderType: {},
}));

jest.mock('../../lib/llm/patterns', () => ({
  LLM_STRUCTURED_BLOCK_REGEX: /never-match-regex/,
}));

import { StreamParser } from '../../lib/llm/stream-parser';

/**
 * 生成模拟 SSE chunk
 */
function generateSSEChunk(text: string): string {
  return `data: ${JSON.stringify({ choices: [{ delta: { content: text } }] })}\n\n`;
}

describe('StreamParser 性能基准测试', () => {
  it('应在 200ms 内解析 1000 个 SSE chunk', () => {
    const parser = new StreamParser('openai');

    // 预生成 1000 个模拟 SSE chunk
    const chunks: string[] = [];
    for (let i = 0; i < 1000; i++) {
      chunks.push(generateSSEChunk(`chunk-${i}-content-text-here-`.repeat(3)));
    }

    const start = performance.now();

    for (const chunk of chunks) {
      parser.process(chunk);
    }

    const duration = performance.now() - start;
    console.log(`StreamParser 解析 1000 个 SSE chunk: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(200);
  });

  it('应在 500ms 内解析 5000 个短文本 chunk', () => {
    const parser = new StreamParser('openai');

    const chunks: string[] = [];
    for (let i = 0; i < 5000; i++) {
      chunks.push(generateSSEChunk(`word `));
    }

    const start = performance.now();

    for (const chunk of chunks) {
      parser.process(chunk);
    }

    const duration = performance.now() - start;
    console.log(`StreamParser 解析 5000 个短 chunk: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(500);
  });

  it('应在 100ms 内处理包含代码块的流', () => {
    const parser = new StreamParser('openai');

    // 模拟包含代码块的流式输出
    const codeContent = [
      'Here is some code:\n',
      '```javascript\n',
      'function hello() {\n',
      '  console.log("world");\n',
      '}\n',
      '```\n',
      'Done.',
    ];

    const start = performance.now();

    // 重复 50 次以增加负载
    for (let round = 0; round < 50; round++) {
      const freshParser = new StreamParser('openai');
      for (const chunk of codeContent) {
        freshParser.process(chunk);
      }
    }

    const duration = performance.now() - start;
    console.log(`StreamParser 处理代码块流 x50: ${duration.toFixed(2)}ms`);

    expect(duration).toBeLessThan(100);
  });
});
