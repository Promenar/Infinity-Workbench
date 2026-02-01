import { McpTool, McpTransport } from '../transport';

export class HttpTransport implements McpTransport {
    private baseUrl: string;

    constructor(baseUrl: string) {
        this.baseUrl = baseUrl;
    }

    /**
     * 构建鲁棒的 URL 路径拼接
     * 继承自原 McpClient 的智能路径处理逻辑
     */
    private buildUrl(path: string): string {
        try {
            const urlObj = new URL(this.baseUrl);

            if (urlObj.pathname !== '/' && urlObj.pathname !== '') {
                return this.baseUrl;
            }

            const normalizedPath = path.startsWith('/') ? path : `/${path}`;
            const fullUrl = new URL(urlObj.origin + urlObj.pathname.replace(/\/+$/, '') + normalizedPath + urlObj.search);
            return fullUrl.toString();
        } catch (e) {
            const base = this.baseUrl.replace(/\/+$/, '');
            const sub = path.startsWith('/') ? path : `/${path}`;
            return `${base}${sub}`;
        }
    }

    private getHeaders(): Record<string, string> {
        return {
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream, */*',
            'User-Agent': 'Nexara/1.2.0 (MCP-HTTP-Client)'
        };
    }

    async connect(): Promise<void> {
        // HTTP is stateless, no connection needed
        return Promise.resolve();
    }

    async disconnect(): Promise<void> {
        // HTTP is stateless, no disconnection needed
        return Promise.resolve();
    }

    async listTools(): Promise<McpTool[]> {
        const url = this.baseUrl;
        console.log(`[HttpTransport] Syncing tools from: ${url}`);

        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: this.getHeaders(),
                body: JSON.stringify({
                    jsonrpc: '2.0',
                    id: `list-${Date.now()}`,
                    method: 'tools/list',
                    params: {}
                })
            });

            if (!response.ok) {
                const fallbackUrl = this.buildUrl('/tools');
                if (fallbackUrl !== url) {
                    const fallbackRes = await fetch(fallbackUrl, {
                        method: 'POST',
                        headers: this.getHeaders(),
                        body: JSON.stringify({ jsonrpc: '2.0', id: 'list-fallback', method: 'tools/list', params: {} })
                    });
                    if (fallbackRes.ok) {
                        const data = await fallbackRes.json();
                        return this.extractTools(data);
                    }
                }
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const data = await response.json();
            return this.extractTools(data);
        } catch (error) {
            console.error(`[HttpTransport] Failed to list tools:`, error);
            throw error;
        }
    }

    async callTool(name: string, args: any): Promise<any> {
        const url = this.baseUrl;

        const executeCall = async (targetUrl: string) => {
            const body = {
                jsonrpc: '2.0',
                id: Date.now(), // 🔑 修复：某些服务端要求 ID 为 Number
                method: 'tools/call',
                params: {
                    name,
                    arguments: args || {}
                }
            };

            // 🔍 Debug: 打印完整的请求 Body，供用户排查参数结构问题
            console.log(`[HttpTransport] Calling tool '${name}' at ${targetUrl}`);
            console.log(`[HttpTransport] Payload:`, JSON.stringify(body, null, 2));

            return await fetch(targetUrl, {
                method: 'POST',
                headers: this.getHeaders(),
                body: JSON.stringify(body)
            });
        };

        try {
            let response = await executeCall(url);

            if (!response.ok && [404, 405, 403].includes(response.status)) {
                const fallbackUrl = this.buildUrl('/tools/call');
                if (fallbackUrl !== url) {
                    console.log(`[HttpTransport] Retrying with sub-path fallback: ${fallbackUrl}`);
                    response = await executeCall(fallbackUrl);
                }
            }

            if (!response.ok) {
                const errorText = await response.text();
                console.error(`[HttpTransport] Tool call failed with ${response.status}:`, errorText);
                throw new Error(`HTTP error! status: ${response.status}, body: ${errorText.substring(0, 200)}`);
            }

            const data = await response.json();
            if (data.error) {
                console.error(`[HttpTransport] JSON-RPC Error:`, data.error);
                throw new Error(data.error.message || 'Unknown MCP error');
            }

            return data.result;
        } catch (error) {
            console.error(`[HttpTransport] Tool call failed (${name}):`, error);
            throw error;
        }
    }

    private extractTools(data: any): McpTool[] {
        if (data.result && Array.isArray(data.result.tools)) {
            return data.result.tools;
        }
        if (Array.isArray(data.tools)) {
            return data.tools;
        }
        if (Array.isArray(data)) {
            return data;
        }
        return [];
    }
}
