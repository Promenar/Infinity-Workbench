/**
 * 纯 JS 文本处理性能基准测试
 *
 * 不依赖任何源码模块，测量基础文本操作吞吐量。
 */

describe('文本处理基准测试', () => {
  it('应在 50ms 内切分 100KB 文本为 500 字符块', () => {
    const text = 'a'.repeat(100_000);
    const start = performance.now();
    const chunks: string[] = [];
    for (let i = 0; i < text.length; i += 500) {
      chunks.push(text.slice(i, i + 500));
    }
    const duration = performance.now() - start;
    console.log(`切分 100KB 文本: ${duration.toFixed(2)}ms, ${chunks.length} 块`);
    expect(duration).toBeLessThan(50);
    expect(chunks.length).toBe(200);
  });

  it('应在 100ms 内解析 10000 元素 JSON 数组', () => {
    const arr = Array.from({ length: 10000 }, (_, i) => ({
      id: i,
      name: `item-${i}`,
      value: Math.random(),
    }));
    const json = JSON.stringify(arr);
    const start = performance.now();
    const parsed = JSON.parse(json);
    const duration = performance.now() - start;
    console.log(`JSON.parse 10000 元素: ${duration.toFixed(2)}ms`);
    expect(duration).toBeLessThan(100);
    expect(parsed).toHaveLength(10000);
  });

  it('应在 50ms 内执行 1000 次正则匹配', () => {
    const text = 'The quick brown fox jumps over the lazy dog. '.repeat(100);
    const start = performance.now();
    let count = 0;
    for (let i = 0; i < 1000; i++) {
      const matches = text.match(/\b\w{4,}\b/g);
      count += matches ? matches.length : 0;
    }
    const duration = performance.now() - start;
    console.log(`1000 次正则匹配: ${duration.toFixed(2)}ms, 总匹配: ${count}`);
    expect(duration).toBeLessThan(50);
  });

  it('应在 50ms 内对 10000 个字符串执行 trim + replace', () => {
    const inputs: string[] = [];
    for (let i = 0; i < 10000; i++) {
      inputs.push(`  some text with <tag>content</tag> and spaces ${i}  `);
    }

    const start = performance.now();
    const results: string[] = [];
    for (const input of inputs) {
      results.push(input.trim().replace(/<[^>]+>/g, ''));
    }
    const duration = performance.now() - start;
    console.log(`10000 次 trim+replace: ${duration.toFixed(2)}ms`);
    expect(duration).toBeLessThan(50);
    expect(results).toHaveLength(10000);
    expect(results[0]).not.toContain('<tag>');
  });

  it('应在 200ms 内拼接 10000 个字符串片段', () => {
    const start = performance.now();
    let result = '';
    for (let i = 0; i < 10000; i++) {
      result += `fragment-${i} `;
    }
    const duration = performance.now() - start;
    console.log(`拼接 10000 个字符串: ${duration.toFixed(2)}ms, 总长度: ${result.length}`);
    expect(duration).toBeLessThan(200);
    expect(result.length).toBeGreaterThan(0);
  });
});
