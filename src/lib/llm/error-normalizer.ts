/**
 * 错误分类
 */
export enum ErrorCategory {
  NETWORK = 'network',
  AUTH = 'auth',
  RATE_LIMIT = 'rate_limit',
  INVALID_REQUEST = 'invalid_request',
  SERVER_ERROR = 'server_error',
  QUOTA_EXCEEDED = 'quota_exceeded',
  TIMEOUT = 'timeout',
  UNKNOWN = 'unknown',
}

/**
 * 标准化后的错误
 */
export interface NormalizedError {
  category: ErrorCategory;
  message: string; // 用户友好消息
  technicalMessage: string; // 技术/调试消息
  retryable: boolean;
  retryAfter?: number; // 重试等待时间（秒）
}

/**
 * 错误标准化器
 *
 * 职责：将各种错误（网络、API、超时等）标准化为统一格式
 * 提供用户友好的错误消息和重试建议
 */
export class ErrorNormalizer {
  /**
   * 标准化错误
   *
   * @param error 原始错误对象
   * @param providerType Provider 类型（可选，用于特定错误处理）
   * @returns 标准化后的错误
   */
  static normalize(error: any, providerType?: string): NormalizedError {
    const errorMsg = error.message || error.toString();
    const errorStatus = error.status || error.statusCode;

    // 1. 网络错误
    if (this.isNetworkError(error, errorMsg)) {
      return {
        category: ErrorCategory.NETWORK,
        message: '网络连接失败，请检查您的网络设置',
        technicalMessage: errorMsg,
        retryable: true,
      };
    }

    // 2. 鉴权错误
    if (this.isAuthError(error, errorStatus, errorMsg)) {
      return {
        category: ErrorCategory.AUTH,
        message: 'API 密钥无效或已过期，请检查设置',
        technicalMessage: `${errorStatus}: ${errorMsg}`,
        retryable: false,
      };
    }

    // 3. 限流错误
    if (this.isRateLimitError(error, errorStatus, errorMsg)) {
      const retryAfter = this.extractRetryAfter(error) || 60;
      const waitTime = this.formatWaitTime(retryAfter);
      return {
        category: ErrorCategory.RATE_LIMIT,
        message: `请求过于频繁，请等待 ${waitTime} 后重试`,
        technicalMessage: errorMsg,
        retryable: true,
        retryAfter,
      };
    }

    // 4. 配额超限
    if (this.isQuotaError(errorMsg)) {
      return {
        category: ErrorCategory.QUOTA_EXCEEDED,
        message: 'API 配额已用尽，请升级套餐或明日再试',
        technicalMessage: errorMsg,
        retryable: false,
      };
    }

    // 5. 超时错误
    if (this.isTimeoutError(error, errorMsg)) {
      return {
        category: ErrorCategory.TIMEOUT,
        message: '请求超时，请重试',
        technicalMessage: errorMsg,
        retryable: true,
      };
    }

    // 6. 请求格式错误（4xx）
    if (errorStatus >= 400 && errorStatus < 500) {
      return {
        category: ErrorCategory.INVALID_REQUEST,
        message: '请求格式错误，请检查输入内容',
        technicalMessage: errorMsg,
        retryable: false,
      };
    }

    // 7. 服务器错误（5xx）
    if (errorStatus >= 500) {
      return {
        category: ErrorCategory.SERVER_ERROR,
        message: 'API 服务暂时不可用，请稍后重试',
        technicalMessage: errorMsg,
        retryable: true,
      };
    }

    // 8. 未知错误
    return {
      category: ErrorCategory.UNKNOWN,
      message: '发生未知错误，请重试',
      technicalMessage: errorMsg,
      retryable: true,
    };
  }

  /**
   * 判断是否为网络错误
   */
  private static isNetworkError(error: any, msg: string): boolean {
    return (
      msg.includes('Network') ||
      msg.includes('fetch') ||
      msg.includes('connection') ||
      msg.includes('ERR_NETWORK') ||
      error.name === 'NetworkError' ||
      error.code === 'ENOTFOUND' ||
      error.code === 'ECONNREFUSED'
    );
  }

  /**
   * 判断是否为鉴权错误
   */
  private static isAuthError(error: any, status: number, msg: string): boolean {
    return (
      status === 401 ||
      status === 403 ||
      msg.includes('401') ||
      msg.includes('403') ||
      msg.includes('Unauthorized') ||
      msg.includes('Forbidden') ||
      msg.includes('authentication') ||
      msg.includes('API key')
    );
  }

  /**
   * 判断是否为限流错误
   */
  private static isRateLimitError(error: any, status: number, msg: string): boolean {
    return (
      status === 429 ||
      msg.includes('429') ||
      msg.includes('rate limit') ||
      msg.includes('too many requests') ||
      msg.includes('throttle')
    );
  }

  /**
   * 判断是否为配额错误
   */
  private static isQuotaError(msg: string): boolean {
    return (
      msg.includes('quota') ||
      msg.includes('limit exceeded') ||
      msg.includes('insufficient_quota') ||
      msg.includes('billing')
    );
  }

  /**
   * 判断是否为超时错误
   */
  private static isTimeoutError(error: any, msg: string): boolean {
    return (
      msg.includes('timeout') ||
      msg.includes('ETIMEDOUT') ||
      error.name === 'TimeoutError' ||
      error.code === 'ETIMEDOUT'
    );
  }

  /**
   * 提取重试等待时间
   */
  private static extractRetryAfter(error: any): number | undefined {
    // 从 HTTP Header 提取
    const retryAfterHeader =
      error.headers?.['retry-after'] || error.response?.headers?.['retry-after'];
    if (retryAfterHeader) {
      const parsed = parseInt(retryAfterHeader);
      if (!isNaN(parsed)) {
        return parsed;
      }
    }

    // 从错误消息中提取 "retry after XX seconds" 模式
    const retryAfterMatch = error.message?.match(/retry after (\d+) seconds/i);
    if (retryAfterMatch) {
      return parseInt(retryAfterMatch[1]);
    }

    // 从错误消息中提取 "wait XX seconds" 模式（如 "Please wait 43877 seconds before retrying"）
    const waitMatch = error.message?.match(/wait (\d+) seconds/i);
    if (waitMatch) {
      return parseInt(waitMatch[1]);
    }

    // 从响应体中提取（针对429错误的JSON响应）
    if (error.response || error.statusText) {
      const responseText = error.response || error.statusText || '';
      const waitInResponseMatch = responseText.match(/wait (\d+) seconds/i);
      if (waitInResponseMatch) {
        return parseInt(waitInResponseMatch[1]);
      }
    }

    return undefined;
  }

  /**
   * 格式化等待时间为友好显示
   */
  private static formatWaitTime(seconds: number): string {
    if (seconds < 60) {
      return `${seconds} 秒`;
    } else if (seconds < 3600) {
      const minutes = Math.ceil(seconds / 60);
      return `${minutes} 分钟`;
    } else if (seconds < 86400) {
      const hours = Math.ceil(seconds / 3600);
      return `${hours} 小时`;
    } else {
      const days = Math.ceil(seconds / 86400);
      return `${days} 天`;
    }
  }
}
