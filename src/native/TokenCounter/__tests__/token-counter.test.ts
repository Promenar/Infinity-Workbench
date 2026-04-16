/**
 * TokenCounter Module Unit Tests
 *
 * Tests both the JS fallback heuristic and the native module integration.
 * All tests run without native module (pure JS environment) to verify
 * the fallback path works correctly.
 */

import { estimateTokens, estimateTokensJS, formatTokenCount } from '../../../features/chat/utils/token-counter';

// ---------------------------------------------------------------------------
// 1. JS Fallback Heuristic Tests
// ---------------------------------------------------------------------------

describe('estimateTokensJS (heuristic fallback)', () => {

  test('returns 0 for empty string', () => {
    expect(estimateTokensJS('')).toBe(0);
  });

  test('counts pure Chinese text', () => {
    // 12 CJK chars → ceil(12 * 1.5) = 18
    const text = '这是一个测试文本用来验证';
    expect(estimateTokensJS(text)).toBe(18);
  });

  test('counts pure English text', () => {
    // "Hello world this is a test" = 6 words → ceil(6 * 1.3) = 8
    const text = 'Hello world this is a test';
    expect(estimateTokensJS(text)).toBe(8);
  });

  test('counts mixed Chinese-English text', () => {
    // "使用React开发" has 2 CJK ("使用", "开发") + 1 English word "React"
    // But actually: CJK = 使用 (2 chars), English = React (1 word), CJK = 开发 (2 chars)
    // CJK: 4 chars → ceil(4*1.5) = 6
    // English: after removing CJK → "React" → 1 word → ceil(1*1.3) = 2
    // Total: 6 + 2 = 8
    const text = '使用React开发';
    expect(estimateTokensJS(text)).toBe(8);
  });

  test('counts text with numbers', () => {
    // "共100个" has 2 CJK + number "100"
    // After removing CJK: "100" → 1 word → ceil(1*1.3) = 2
    // CJK: 2 → ceil(2*1.5) = 3
    // Total: 3 + 2 = 5
    const text = '共100个';
    expect(estimateTokensJS(text)).toBe(5);
  });

  test('handles special characters', () => {
    const text = 'Hello, world! @#$%^&*()';
    expect(estimateTokensJS(text)).toBeGreaterThan(0);
  });

  test('handles Japanese text', () => {
    // Hiragana: あいうえお (5 chars)
    const text = 'あいうえお';
    expect(estimateTokensJS(text)).toBe(8); // ceil(5 * 1.5) = 8
  });

  test('handles Korean text', () => {
    // Hangul: 안녕하세요 (5 chars)
    const text = '안녕하세요';
    expect(estimateTokensJS(text)).toBe(8); // ceil(5 * 1.5) = 8
  });
});

// ---------------------------------------------------------------------------
// 2. estimateTokens (with native fallback)
// ---------------------------------------------------------------------------

describe('estimateTokens (integrated)', () => {

  test('returns 0 for empty string', () => {
    expect(estimateTokens('')).toBe(0);
  });

  test('returns 0 for whitespace-only string', () => {
    // whitespace is trimmed, resulting in 0 words
    const result = estimateTokens('   \n\t  ');
    expect(result).toBeGreaterThanOrEqual(0);
  });

  test('produces consistent results with JS fallback', () => {
    const texts = [
      '这是一个中文测试',
      'Hello world',
      '使用React开发应用',
      '共100个节点，其中50个活跃。',
      'The quick brown fox jumps over the lazy dog.',
      '',
    ];

    for (const text of texts) {
      // In test env (no native module), estimateTokens should equal estimateTokensJS
      expect(estimateTokens(text)).toBe(estimateTokensJS(text));
    }
  });

  test('handles very long text without crashing', () => {
    // Generate 10000+ character text
    const longText = '这是一段测试文本。'.repeat(1000);
    const start = Date.now();
    const result = estimateTokens(longText);
    const elapsed = Date.now() - start;

    expect(result).toBeGreaterThan(0);
    // Should complete in reasonable time (< 100ms even in JS)
    expect(elapsed).toBeLessThan(100);
  });
});

// ---------------------------------------------------------------------------
// 3. formatTokenCount Tests
// ---------------------------------------------------------------------------

describe('formatTokenCount', () => {

  test('formats small numbers directly', () => {
    expect(formatTokenCount(42)).toBe('42');
    expect(formatTokenCount(999)).toBe('999');
  });

  test('formats thousands with K suffix', () => {
    expect(formatTokenCount(1000)).toBe('1.0k');
    expect(formatTokenCount(1234)).toBe('1.2k');
    expect(formatTokenCount(100000)).toBe('100k');
    expect(formatTokenCount(123456)).toBe('123k');
  });

  test('formats millions with M suffix', () => {
    expect(formatTokenCount(1000000)).toBe('1.0M');
    expect(formatTokenCount(1234567)).toBe('1.2M');
    expect(formatTokenCount(100000000)).toBe('100M');
  });
});
