import { LlmClient, ChatMessage } from '../types';

export class VertexAiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;
    private project?: string;
    private location?: string;
    private keyJson?: string;

    constructor(config: {
        apiKey: string;
        model: string;
        temperature: number;
        baseUrl: string;
        project?: string;
        location?: string;
        keyJson?: string;
    }) {
        this.apiKey = config.apiKey;
        this.baseUrl = config.baseUrl;
        this.model = config.model;
        this.temperature = config.temperature;
        this.project = config.project;
        this.location = config.location;
        this.keyJson = config.keyJson;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: string) => void,
        onError: (err: Error) => void
    ): Promise<void> {
        // TODO: 实现 VertexAI 的 streamChat
        onError(new Error('VertexAI streamChat not yet fully implemented'));
    }

    async testConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
        const start = Date.now();
        try {
            if (!this.baseUrl) throw new Error('Base URL is required');
            if (!this.project) throw new Error('Project ID is required (check your JSON key or settings)');

            let token = this.apiKey;

            // 确保 baseUrl 包含协议
            let cleanBase = this.baseUrl.trim();
            if (!cleanBase.startsWith('http')) cleanBase = `https://${cleanBase}`;

            // 兼容性处理：如果 URL 结尾没有 /v1，根据 Google 规范补上
            if (!cleanBase.includes('/v1')) {
                cleanBase = cleanBase.replace(/\/$/, '') + '/v1';
            }

            const endpoint = `${cleanBase}/projects/${this.project}/locations/${this.location || 'us-central1'}/publishers/google/models/${this.model}:predict`;

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                },
                body: JSON.stringify({
                    // 尝试最通用的 Gemini on Vertex 探测格式
                    contents: [{ role: 'user', parts: [{ text: 'ping' }] }],
                    generationConfig: { maxOutputTokens: 1 }
                }),
            });

            const latency = Date.now() - start;

            if (!response.ok) {
                const errorText = await response.text().catch(() => 'No error body');
                let msg = `HTTP ${response.status}: `;
                if (response.status === 401) msg += "认证失败 (Unauthorized)";
                else if (response.status === 403) msg += "权限不足 (Forbidden) - 请检查服务账号权限";
                else if (response.status === 404) msg += "端点未找到 (404) - 请检查地区或项目 ID";
                else msg += errorText.substring(0, 80);

                return { success: false, latency, error: msg };
            }

            return { success: true, latency };
        } catch (e) {
            const latency = Date.now() - start;
            // 如果是真正的 Network request failed，通常 e 为 TypeError: Network request failed
            return { success: false, latency, error: `网络连接失败: ${(e as Error).message}` };
        }
    }
}
