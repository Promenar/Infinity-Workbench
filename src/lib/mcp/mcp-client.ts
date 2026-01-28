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
     */
    private buildUrl(path: string): string {
        try {
            const urlObj = new URL(this.baseUrl);
            // 确保 path 以 / 开头
            const normalizedPath = path.startsWith('/') ? path : `/${path}`;

            // 🔑 关键修复：URL 构造函数会自动处理 base 为带查询参数的情况
            // 将 path 拼接到 pathname 后，同时维持原来的 searchParams
            const fullUrl = new URL(urlObj.origin + urlObj.pathname.replace(/\/+$/, '') + normalizedPath + urlObj.search);
            return fullUrl.toString();
        } catch (e) {
            // 如果不是合法 URL（可能是相对路径或特殊配置），回退到简单拼接
            const base = this.baseUrl.replace(/\/+$/, '');
            const sub = path.startsWith('/') ? path : `/${path}`;
            return `${base}${sub}`;
        }
    }

    /**
     * 初始化并获取工具列表
     * 遵循 MCP JSON-RPC 2.0 规范，优先尝试 tools/list
     */
    async listTools(): Promise<McpTool[]> {
        const url = this.buildUrl('/tools');
        console.log(`[McpClient] Syncing tools from: ${url}`);

        try {
            // 🔑 增强：同时尝试 GET (REST) 与 POST (JSON-RPC) 兼容多种服务器实现
            const response = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    jsonrpc: '2.0',
                    id: 'list-tools-1',
                    method: 'tools/list',
                    params: {}
                })
            });

            if (!response.ok && response.status === 405) {
                // 如果 POST 不被允许，尝试回退到 GET
                const getResponse = await fetch(url, { method: 'GET' });
                if (!getResponse.ok) throw new Error(`HTTP error! status: ${getResponse.status}`);
                const data = await getResponse.json();
                return this.extractTools(data);
            }

            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
            const data = await response.json();
            return this.extractTools(data);
        } catch (error) {
            console.error(`[McpClient] Failed to list tools from ${url}:`, error);
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
        const url = this.buildUrl('/tools/call');
        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    jsonrpc: '2.0',
                    id: Date.now().toString(),
                    method: 'tools/call',
                    params: { name, arguments: args }
                })
            });

            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

            const data = await response.json();
            if (data.error) throw new Error(data.error.message || 'Unknown MCP error');

            // 标准结果在 data.result.content 中
            return data.result;
        } catch (error) {
            console.error(`[McpClient] Tool call failed (${name}):`, error);
            throw error;
        }
    }
}
