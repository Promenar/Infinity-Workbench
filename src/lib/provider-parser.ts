/**
 * 服务商配置解析与管理工具类
 * 遵循项目准则：简体中文注释，严谨的技术实现。
 */

import { ApiProviderType } from '../store/api-store';

/**
 * 解析 VertexAI (Google Cloud) 服务账号 JSON
 * @param jsonString 原始 JSON 字符串
 */
export const parseVertexAIConfig = (jsonString: string) => {
    try {
        const config = JSON.parse(jsonString);
        // 校验关键字段
        if (!config.project_id || !config.private_key || !config.client_email) {
            throw new Error('无效的 Google Cloud 服务账号 JSON');
        }
        return {
            projectId: config.project_id,
            privateKey: config.private_key,
            clientEmail: config.client_email,
            location: 'us-central1', // 默认区域
        };
    } catch (error) {
        console.error('VertexAI 解析失败:', error);
        throw error;
    }
};

/**
 * 模型服务类：处理各服务商模型拉取逻辑
 */
export class ModelService {
    /**
     * 根据服务商类型拉取模型列表
     * @param type 服务商类型
     * @param apiKey API 密钥
     * @param baseUrl 自定义 Base URL
     */
    static async fetchModels(type: ApiProviderType, apiKey: string, baseUrl?: string) {
        // 对于 Google (VertexAI) 等特殊服务商，目前暂返回预设
        if (type === 'google' || type === 'github-copilot' || type === 'local') {
            return this.getPresetModels(type);
        }

        const url = baseUrl || this.getDefaultBaseUrl(type);
        const endpoint = `${url}/models`;

        try {
            console.log(`正在从 ${endpoint} 拉取 ${type} 的模型...`);

            const response = await fetch(endpoint, {
                method: 'GET',
                headers: {
                    'Authorization': `Bearer ${apiKey}`,
                    'Content-Type': 'application/json'
                }
            });

            if (!response.ok) {
                throw new Error(`HTTP 错误: ${response.status}`);
            }

            const data = await response.json();

            // 解析 OpenAI 兼容格式
            if (data.data && Array.isArray(data.data)) {
                return data.data.map((m: any) => m.id);
            }

            return this.getPresetModels(type);
        } catch (error) {
            console.error(`${type} 模型获取失败:`, error);
            return this.getPresetModels(type); // 降级返回预设
        }
    }

    private static getDefaultBaseUrl(type: ApiProviderType): string {
        const mapping: Record<string, string> = {
            openai: 'https://api.openai.com/v1',
            anthropic: 'https://api.anthropic.com/v1',
            deepseek: 'https://api.deepseek.com',
            moonshot: 'https://api.moonshot.cn/v1',
            zhipu: 'https://open.bigmodel.cn/api/paas/v4',
            siliconflow: 'https://api.siliconflow.cn/v1',
            github: 'https://models.inference.ai.azure.com',
        };
        return mapping[type] || '';
    }

    private static getPresetModels(type: ApiProviderType) {
        // 根据类型返回一些通用的预设模型列表
        const presets: Record<string, string[]> = {
            openai: ['gpt-4o', 'gpt-4o-mini', 'o1-preview', 'o1-mini'],
            deepseek: ['deepseek-chat', 'deepseek-coder'],
            google: ['gemini-1.5-pro', 'gemini-1.5-flash'],
            moonshot: ['moonshot-v1-8k', 'moonshot-v1-32k'],
            zhipu: ['glm-4', 'glm-4-flash'],
            siliconflow: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen2.5-72B-Instruct'],
            github: ['gpt-4o', 'claude-3-5-sonnet'],
        };
        return presets[type] || [];
    }
}

/**
 * Token 计数工具类 (简易实现，后续可集成 tiktoken)
 */
export class TokenCounter {
    /**
     * 粗略估算字符串的 Token 量
     * @param text 文本内容
     */
    static estimateTokens(text: string): number {
        if (!text) return 0;
        // 简易逻辑：中文字符数 * 2 + 单词数
        const chineseCount = (text.match(/[\u4e00-\u9fa5]/g) || []).length;
        const words = text.split(/\s+/).length;
        return chineseCount * 2 + words;
    }
}
