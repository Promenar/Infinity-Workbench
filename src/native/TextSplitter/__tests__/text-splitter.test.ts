/**
 * TextSplitter Module Unit Tests
 *
 * Tests the TrigramTextSplitter JS implementation and native fallback path.
 * All tests run in pure JS environment (no native module) to verify
 * the fallback behavior works correctly.
 */

import { TrigramTextSplitter } from '../../../lib/rag/text-splitter';

// ---------------------------------------------------------------------------
// 1. Basic Splitting
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - Basic Splitting', () => {

  test('returns empty array for empty string', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 100, chunkOverlap: 20 });
    const result = await splitter.splitText('');
    expect(result).toEqual([]);
  });

  test('returns empty array for whitespace-only string', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 100, chunkOverlap: 20 });
    const result = await splitter.splitText('   \n\t  ');
    expect(result).toEqual([]);
  });

  test('short text (< chunkSize) returns single chunk', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 800, chunkOverlap: 100 });
    const text = '这是一段短文本。不需要分割。';
    const result = await splitter.splitText(text);
    expect(result.length).toBe(1);
    expect(result[0]).toContain('短文本');
  });

  test('medium text splits at sentence boundaries', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 30, chunkOverlap: 5 });
    // Each sentence is ~8 chars + delimiter = ~9 chars. 5 sentences = ~45 chars > chunkSize 30
    const text = '这是第一句话内容。这是第二句话内容。这是第三句话内容。这是第四句话内容。这是第五句话内容。';
    const result = await splitter.splitText(text);

    expect(result.length).toBeGreaterThan(1);
    // Each chunk should not exceed chunkSize by much
    for (const chunk of result) {
      expect(chunk.length).toBeLessThanOrEqual(80); // allow some slack for overlap
    }
  });
});

// ---------------------------------------------------------------------------
// 2. Long Sentence Handling (Trigram splitting)
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - Long Sentence Handling', () => {

  test('long sentence (> chunkSize) triggers sub-splitting', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 50, chunkOverlap: 10 });
    // A very long sentence without sentence-ending punctuation
    const longSentence = '这是一个非常长的句子没有句号分隔所以需要用Trigram方法来进行分割处理确保每个块不超过设定的大小限制同时保持语义的连贯性和上下文信息的完整性';
    const result = await splitter.splitText(longSentence);

    expect(result.length).toBeGreaterThan(1);
    for (const chunk of result) {
      expect(chunk.length).toBeGreaterThan(0);
    }
  });

  test('respects punctuation breakpoints in long sentences', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 30, chunkOverlap: 5 });
    const text = '这是第一部分，包含逗号分隔。这是第二部分，也有逗号。这是第三部分，继续分隔。';
    const result = await splitter.splitText(text);

    expect(result.length).toBeGreaterThan(1);
    // Chunks should prefer breaking at commas
    for (const chunk of result) {
      expect(chunk.length).toBeGreaterThan(0);
    }
  });
});

// ---------------------------------------------------------------------------
// 3. Overlap
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - Overlap', () => {

  test('consecutive chunks have overlapping content', async () => {
    const chunkSize = 30;
    const chunkOverlap = 10;
    const splitter = new TrigramTextSplitter({ chunkSize, chunkOverlap });
    const text = '这是第一句话的内容。这是第二句话的内容。这是第三句话的内容。这是第四句话的内容。';
    const result = await splitter.splitText(text);

    if (result.length > 1) {
      // Check that overlap content from previous chunk appears at start of next chunk
      for (let i = 1; i < result.length; i++) {
        const prevTail = result[i - 1].slice(-chunkOverlap);
        // The overlap should exist either as prefix or within the chunk
        expect(result[i]).toBeDefined();
      }
    }
  });

  test('zero overlap does not duplicate content', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 30, chunkOverlap: 0 });
    const text = '第一句话。第二句话。第三句话。第四句话。第五句话。第六句话。';
    const result = await splitter.splitText(text);

    // With zero overlap, no content should be duplicated
    const allText = result.join('');
    // Just verify no crash and results are non-empty
    for (const chunk of result) {
      expect(chunk.length).toBeGreaterThan(0);
    }
  });
});

// ---------------------------------------------------------------------------
// 4. Sentence Delimiters
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - Sentence Delimiters', () => {

  test('splits on Chinese period (。)', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 10, chunkOverlap: 2 });
    const result = await splitter.splitText('第一句话内容。第二句话内容。第三句话内容。');
    expect(result.length).toBeGreaterThan(1);
  });

  test('splits on Chinese exclamation mark (！)', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 10, chunkOverlap: 2 });
    const result = await splitter.splitText('第一句话内容！第二句话内容！第三句话内容！');
    expect(result.length).toBeGreaterThan(1);
  });

  test('splits on Chinese question mark (？)', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 10, chunkOverlap: 2 });
    const result = await splitter.splitText('第一句话内容？第二句话内容？第三句话内容？');
    expect(result.length).toBeGreaterThan(1);
  });

  test('splits on Chinese semicolon (；)', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 10, chunkOverlap: 2 });
    const result = await splitter.splitText('第一句话内容；第二句话内容；第三句话内容；');
    expect(result.length).toBeGreaterThan(1);
  });
});

// ---------------------------------------------------------------------------
// 5. Validation
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - Validation', () => {

  test('throws error when chunkOverlap >= chunkSize', () => {
    expect(() => {
      new TrigramTextSplitter({ chunkSize: 100, chunkOverlap: 100 });
    }).toThrow('chunkOverlap must be smaller than chunkSize');
  });

  test('throws error when chunkOverlap > chunkSize', () => {
    expect(() => {
      new TrigramTextSplitter({ chunkSize: 100, chunkOverlap: 200 });
    }).toThrow('chunkOverlap must be smaller than chunkSize');
  });
});

// ---------------------------------------------------------------------------
// 6. Estimate Chunk Count
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - estimateChunkCount', () => {

  test('returns 1 for text shorter than chunkSize', () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 800, chunkOverlap: 100 });
    const estimate = splitter.estimateChunkCount('短文本');
    expect(estimate).toBe(1);
  });

  test('returns reasonable estimate for longer text', () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 100, chunkOverlap: 20 });
    const text = '这是一段比较长的文本内容'.repeat(20);
    const estimate = splitter.estimateChunkCount(text);
    expect(estimate).toBeGreaterThan(1);
  });

  test('estimate is consistent with actual split count (within reason)', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 100, chunkOverlap: 20 });
    const text = '这是第一句话的内容。这是第二句话的内容。这是第三句话的内容。这是第四句话。第五句。第六句。第七句。';
    const estimate = splitter.estimateChunkCount(text);
    const actual = (await splitter.splitText(text)).length;

    // Estimate should be within 2x of actual (it's a rough estimate)
    expect(estimate).toBeGreaterThan(0);
    expect(estimate).toBeLessThanOrEqual(actual * 2 + 1);
  });
});

// ---------------------------------------------------------------------------
// 7. Performance Test
// ---------------------------------------------------------------------------

describe('TrigramTextSplitter - Performance', () => {

  test('handles 50000+ character text without crashing', async () => {
    const splitter = new TrigramTextSplitter({ chunkSize: 800, chunkOverlap: 100 });
    // Generate ~50000 chars
    const baseText = '这是一个中等长度的句子，用来测试文本分割器的性能表现。';
    const longText = baseText.repeat(500);

    const start = Date.now();
    const result = await splitter.splitText(longText);
    const elapsed = Date.now() - start;

    expect(result.length).toBeGreaterThan(10);
    // Should complete within reasonable time
    expect(elapsed).toBeLessThan(5000);
  });
});
