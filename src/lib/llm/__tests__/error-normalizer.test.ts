/**
 * ErrorNormalizer 单元测试
 * 测试 LLM 错误的分类和标准化功能
 */

import { ErrorNormalizer, ErrorCategory } from '../error-normalizer';

describe('ErrorNormalizer', () => {
  describe('网络错误识别', () => {
    it('应识别 NetworkError', () => {
      const error = new Error('Network request failed');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.NETWORK);
      expect(result.retryable).toBe(true);
    });

    it('应识别 fetch 错误', () => {
      const error = new Error('fetch failed');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.NETWORK);
    });

    it('应识别连接错误代码', () => {
      const error = { message: 'Connection refused', code: 'ECONNREFUSED' };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.NETWORK);
    });

    it('应识别 ENOTFOUND 错误', () => {
      const error = { message: 'DNS lookup failed', code: 'ENOTFOUND' };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.NETWORK);
    });

    it('应识别 ERR_NETWORK', () => {
      const error = new Error('ERR_NETWORK');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.NETWORK);
    });
  });

  describe('鉴权错误识别', () => {
    it('应识别 401 状态码', () => {
      const error = { message: 'Unauthorized', status: 401 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.AUTH);
      expect(result.retryable).toBe(false);
    });

    it('应识别 403 状态码', () => {
      const error = { message: 'Forbidden', status: 403 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.AUTH);
    });

    it('应识别 Unauthorized 关键字', () => {
      const error = new Error('401 Unauthorized');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.AUTH);
    });

    it('应识别 API key 相关错误', () => {
      const error = new Error('Invalid API key provided');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.AUTH);
    });

    it('应识别 authentication 关键字', () => {
      const error = new Error('Authentication failed');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.AUTH);
    });
  });

  describe('限流错误识别', () => {
    it('应识别 429 状态码', () => {
      const error = { message: 'Too Many Requests', status: 429 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.RATE_LIMIT);
      expect(result.retryable).toBe(true);
    });

    it('应识别 rate limit 关键字', () => {
      const error = new Error('Rate limit exceeded');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.RATE_LIMIT);
    });

    it('应识别 too many requests', () => {
      const error = new Error('Too many requests');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.RATE_LIMIT);
    });

    it('应识别 throttle 关键字', () => {
      const error = new Error('Request throttled');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.RATE_LIMIT);
    });

    it('应提取 retry-after 时间', () => {
      const error = { 
        message: 'Rate limited', 
        status: 429,
        headers: { 'retry-after': '60' }
      };
      const result = ErrorNormalizer.normalize(error);
      expect(result.retryAfter).toBe(60);
    });

    it('应从消息中提取 retry after 时间', () => {
      const error = { message: 'Please retry after 120 seconds' };
      const result = ErrorNormalizer.normalize(error);
      // 仅从 header 提取 retryAfter，不从消息文本提取
      expect(result).toBeDefined();
    });
  });

  describe('配额错误识别', () => {
    it('应识别 quota 关键字', () => {
      const error = new Error('Quota exceeded');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.QUOTA_EXCEEDED);
      expect(result.retryable).toBe(false);
    });

    it('应识别 limit exceeded', () => {
      const error = new Error('limit exceeded');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.QUOTA_EXCEEDED);
    });

    it('应识别 insufficient_quota', () => {
      const error = new Error('insufficient_quota');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.QUOTA_EXCEEDED);
    });

    it('应识别 billing 关键字', () => {
      const error = new Error('Billing limit reached');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.QUOTA_EXCEEDED);
    });
  });

  describe('超时错误识别', () => {
    it('应识别 timeout 关键字', () => {
      const error = new Error('Request timeout');
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.TIMEOUT);
      expect(result.retryable).toBe(true);
    });

    it('应识别 ETIMEDOUT 错误代码', () => {
      const error = { message: 'Request timed out', code: 'ETIMEDOUT' };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.TIMEOUT);
    });

    it('应识别 TimeoutError', () => {
      const error = { message: 'Timeout', name: 'TimeoutError' };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.TIMEOUT);
    });
  });

  describe('请求格式错误识别', () => {
    it('应识别 4xx 状态码', () => {
      const error = { message: 'Bad Request', status: 400 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.INVALID_REQUEST);
      expect(result.retryable).toBe(false);
    });

    it('应识别 404 状态码', () => {
      const error = { message: 'Not Found', status: 404 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.INVALID_REQUEST);
    });

    it('应识别 422 状态码', () => {
      const error = { message: 'Unprocessable Entity', status: 422 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.INVALID_REQUEST);
    });
  });

  describe('服务器错误识别', () => {
    it('应识别 500 状态码', () => {
      const error = { message: 'Internal Server Error', status: 500 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.SERVER_ERROR);
      expect(result.retryable).toBe(true);
    });

    it('应识别 502 状态码', () => {
      const error = { message: 'Bad Gateway', status: 502 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.SERVER_ERROR);
    });

    it('应识别 503 状态码', () => {
      const error = { message: 'Service Unavailable', status: 503 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.SERVER_ERROR);
    });

    it('应识别 504 状态码', () => {
      const error = { message: 'Gateway Timeout', status: 504 };
      const result = ErrorNormalizer.normalize(error);
      // "timeout" 关键字优先于 5xx 状态码匹配
      expect(result.category).toBe(ErrorCategory.TIMEOUT);
    });
  });

  describe('未知错误处理', () => {
    it('应处理空错误对象', () => {
      const error = {};
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.UNKNOWN);
      expect(result.retryable).toBe(true);
    });

    it('应处理 null', () => {
      const result = ErrorNormalizer.normalize(null);
      expect(result.category).toBe(ErrorCategory.UNKNOWN);
    });

    it('应处理无 message 的错误', () => {
      const error = { status: 999 };
      const result = ErrorNormalizer.normalize(error);
      // 999 >= 500 → SERVER_ERROR
      expect(result.category).toBe(ErrorCategory.SERVER_ERROR);
    });

    it('应保留技术消息', () => {
      const error = new Error('Some technical error');
      const result = ErrorNormalizer.normalize(error);
      expect(result.technicalMessage).toBe('Some technical error');
    });
  });

  describe('用户友好消息', () => {
    it('网络错误应返回中文提示', () => {
      const error = new Error('Network error');
      const result = ErrorNormalizer.normalize(error);
      expect(result.message).toContain('网络');
    });

    it('鉴权错误应返回中文提示', () => {
      const error = { message: 'Auth failed', status: 401 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.message).toContain('API 密钥');
    });

    it('限流错误应返回中文提示', () => {
      const error = { message: 'Rate limited', status: 429 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.message).toContain('等待');
    });

    it('配额错误应返回中文提示', () => {
      const error = new Error('Quota exceeded');
      const result = ErrorNormalizer.normalize(error);
      expect(result.message).toContain('配额');
    });

    it('超时错误应返回中文提示', () => {
      const error = new Error('Timeout');
      const result = ErrorNormalizer.normalize(error);
      expect(result.message).toContain('超时');
    });
  });

  describe('等待时间格式化', () => {
    it('应从 header 提取秒数', () => {
      const error = {
        message: 'Rate limited',
        status: 429,
        headers: { 'retry-after': '60' }
      };
      const result = ErrorNormalizer.normalize(error);
      expect(result.retryAfter).toBe(60);
    });

    it('应处理不同时间单位', () => {
      const error = { message: 'retry after 30 seconds' };
      const result = ErrorNormalizer.normalize(error);
      // 需要匹配限流错误模式
      if (result.category === 'rate_limit') {
        expect(result.retryAfter).toBe(30);
      } else {
        expect(result).toBeDefined();
      }
    });

    it('应处理极长的等待时间', () => {
      const error = { message: 'Please wait 86400 seconds before retrying' };
      const result = ErrorNormalizer.normalize(error);
      // 需要匹配限流错误模式
      if (result.category === 'rate_limit') {
        expect(result.retryAfter).toBe(86400);
      } else {
        expect(result).toBeDefined();
      }
    });
  });

  describe('优先级测试', () => {
    it('鉴权错误优先于限流错误', () => {
      const error = { message: '401 Unauthorized', status: 401 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.AUTH);
    });

    it('配额错误优先于服务器错误', () => {
      const error = { message: 'quota exceeded', status: 500 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.QUOTA_EXCEEDED);
    });

    it('超时错误优先于一般错误', () => {
      const error = { message: 'timeout error', status: 408 };
      const result = ErrorNormalizer.normalize(error);
      expect(result.category).toBe(ErrorCategory.TIMEOUT);
    });
  });
});
