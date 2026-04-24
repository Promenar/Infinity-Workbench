/**
 * WebView Bridge 通信层
 *
 * 封装 WebView 与 RN 之间的双向消息通信。
 *
 * 发送方向（Web → RN）：window.ReactNativeWebView.postMessage()
 * 接收方向（RN → Web）：window.addEventListener('message', ...)
 */

import type { WebToRNMessage, RNToWebMessage } from '../types/bridge';

type MessageHandler = (msg: RNToWebMessage) => void;

let messageHandler: MessageHandler | null = null;

/**
 * 向 RN 侧发送消息
 */
export function postToRN(msg: WebToRNMessage): void {
  try {
    const json = JSON.stringify(msg);
    if (window.ReactNativeWebView) {
      window.ReactNativeWebView.postMessage(json);
    } else {
      // 开发模式 fallback：控制台输出
      console.log('[Bridge → RN]', msg.type, msg);
    }
  } catch (e) {
    console.error('[Bridge] postToRN error:', e);
  }
}

/**
 * 通知 RN 侧 WebView 已就绪
 */
export function sendReady(): void {
  postToRN({ type: 'READY' });
}

/**
 * 注册 RN → Web 消息处理器
 */
export function onRNMessage(handler: MessageHandler): void {
  messageHandler = handler;
}

/**
 * 初始化 Bridge 监听
 *
 * 在 App 挂载时调用一次。
 */
export function initBridge(): void {
  const handler = (event: MessageEvent) => {
    try {
      const data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
      if (data && typeof data.type === 'string') {
        messageHandler?.(data as RNToWebMessage);
      }
    } catch {
      // 忽略非 JSON 消息
    }
  };

  window.addEventListener('message', handler);
}

// 类型声明：React Native WebView 注入的全局对象
declare global {
  interface Window {
    ReactNativeWebView?: {
      postMessage: (message: string) => void;
    };
  }
}
