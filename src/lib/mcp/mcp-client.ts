export interface McpTool {
    name: string;
    description: string;
    inputSchema: any;
}

export class McpClient {
    private baseUrl: string;

    constructor(baseUrl: string) {
        // 🔑 逻辑加固：保留完整原始 URL，由调用处精细化处理路径拼接
        this.baseUrl = baseUrl;
    }

    /**
     * 构建鲁棒的 URL 路径拼接
     * 🔑 框架增强：实现路径自适应。如果 baseUrl 已经包含了特定端点（如 /mcp），
     * 则后续的 JSON-RPC 请求应优先尝试发送到该根路径，而非盲目拼接。
     */
    private buildUrl(path: string): string {
        try {
            const urlObj = new URL(this.baseUrl);

            // 如果 baseUrl 本身已经指向了一个深层路径（如 .../mcp）， 
            // 且该路径暗示它是一个单一 RPC 端点，则不应强制拼接 /tools 或 /tools/call
            // 在通用框架下，我们将 baseUrl 视为首选 RPC 入口
            if (urlObj.pathname !== '/' && urlObj.pathname !== '') {
                // 如果路径以常见 RPC 端点结尾，或者调用方请求的是标准子路径，进行智能融合
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

    /**
     * 获取标准 HTTP 头部
     */
    private getHeaders(): Record<string, string> {
        return {
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream, */*',
            'User-Agent': 'Nexara/1.2.0 (MCP-Generic-Client)'
        };
    }

    /**
     * 初始化并获取工具列表
     * 遵循 MCP JSON-RPC 2.0 规范，优先尝试 tools/list
     */
    async listTools(): Promise<McpTool[]> {
        // 🔑 框架增强：优先尝试根路径 POST，这是 MCP-over-HTTP 的最标准做法
        const url = this.baseUrl;
        console.log(`[McpClient] Syncing tools from (adaptive): ${url}`);

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

            // 🔑 容错逻辑：如果根路径 POST 失败（404/405），回退到传统 /tools 子路径尝试
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
            console.error(`[McpClient] Failed to list tools:`, error);
            throw error;
        }
    }

    private extractTools(data: any): McpTool[] {
        // 处理 JSON-RPC 响应格式
        if (data.result && Array.isArray(data.result.tools)) {
            return data.result.tools;
        }
        // 处理简洁 REST 响应格式
        if (Array.isArray(data.tools)) {
            return data.tools;
        }
        if (Array.isArray(data)) {
            return data;
        }
        return [];
    }

    /**
     * 调用指定工具
     */
    async callTool(name: string, args: any): Promise<any> {
        // 🔑 框架增强：通用请求端点解析
        // 很多 MCP 服务器仅在根端点监听 RPC，并不区分 /tools/call 路径
        const url = this.baseUrl;

        const executeCall = async (targetUrl: string) => {
            return await fetch(targetUrl, {
                method: 'POST',
                headers: this.getHeaders(),
                body: JSON.stringify({
                    jsonrpc: '2.0',
                    id: `call-${Date.now()}-${name}`,
                    method: 'tools/call',
                    params: { name, arguments: args }
                })
            });
        };

        try {
            let response = await executeCall(url);

            // 🔑 路径自适应：如果根路径返回 404/405/403，尝试标准子路径
            if (!response.ok && [404, 405, 403].includes(response.status)) {
                const fallbackUrl = this.buildUrl('/tools/call');
                if (fallbackUrl !== url) {
                    console.log(`[McpClient] Retrying with sub-path fallback: ${fallbackUrl}`);
                    response = await executeCall(fallbackUrl);
                }
            }

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`HTTP error! status: ${response.status}, body: ${errorText.substring(0, 100)}`);
            }

            const data = await response.json();
            if (data.error) throw new Error(data.error.message || 'Unknown MCP error');

            return data.result;
        } catch (error) {
            console.error(`[McpClient] Tool call failed (${name}):`, error);
            throw error;
        }
    }
}
