
/**
 * Protocol Module
 * 定义严格的交互契约（思维链、任务管理、格式化）。
 * 🌐 已国际化：所有协议通过 i18n 字典动态选择语言。
 */

import { getPrompts, type PromptLang } from '../i18n';

export const ProtocolModule = {
  getThinkingProtocol(lang?: PromptLang): string {
    return getPrompts(lang).protocols.thinking;
  },

  getTaskProtocol(hasTaskTool: boolean = true, lang?: PromptLang): string {
    return getPrompts(lang).protocols.task(hasTaskTool);
  },

  getFormattingProtocol(lang?: PromptLang): string {
    return getPrompts(lang).protocols.formatting;
  }
};
