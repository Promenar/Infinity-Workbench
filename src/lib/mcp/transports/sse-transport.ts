import EventSource from 'react-native-sse';
import { McpTool, McpTransport } from '../transport';

interface JsonRpcRequest {
    jsonrpc: '2.0';
    id: string | number;
    method: string;
    params?: any;
}

interface JsonRpcResponse {
    jsonrpc: '2.0';
    id: string | number;
    result?: any;
    error?: {
        code: number;
        message: string;
        data?: any;
    };
}

export class SseTransport implements McpTransport {
    private baseUrl: string;
    private eventSource: EventSource | null = null;
    private endpoint: string | null = null; // POST endpoint received from SSE
    private pendingRequests: Map<string | number, { resolve: (val: any) => void; reject: (err: any) => void }> = new Map();
    private isConnected: boolean = false;
    private connectionPromise: Promise<void> | null = null;

    constructor(baseUrl: string) {
        this.baseUrl = baseUrl;
    }

    async connect(): Promise<void> {
        if (this.isConnected) return;
        if (this.connectionPromise) return this.connectionPromise;

        this.connectionPromise = new Promise((resolve, reject) => {
            console.log(`[SseTransport] Connecting to SSE: ${this.baseUrl}`);

            // Allow self-signed certs in dev (optional, be careful in prod)
            this.eventSource = new EventSource(this.baseUrl);

            const onOpen = () => {
                console.log('[SseTransport] SSE Connection Opened');
                // Wait for 'endpoint' event to consider fully connected for MCP
            };

            const onMessage = (event: any) => {
                // Handle standard JSON-RPC messages pushed via 'message' event
                if (event.type === 'message' && event.data) {
                    try {
                        const data = JSON.parse(event.data);
                        this.handleIncomingMessage(data);
                    } catch (e) {
                        console.error('[SseTransport] Failed to parse message:', e);
                    }
                }
            };

            const onEndpoint = (event: any) => {
                console.log('[SseTransport] Received endpoint event:', event.data);
                // MCP SSE spec: server sends 'endpoint' event with the relative URI for POST requests
                // Treat this as the signal that the handshake is complete
                this.endpoint = event.data;
                this.isConnected = true;
                resolve();
            };

            const onError = (event: any) => {
                console.error('[SseTransport] SSE Error:', event);
                if (!this.isConnected) {
                    reject(new Error('Failed to connect to SSE stream'));
                    this.connectionPromise = null;
                }
                // Optional: Reconnect logic could go here, but rely on user retry for now
                this.disconnect();
            };

            this.eventSource.addEventListener('open', onOpen);
            this.eventSource.addEventListener('message', onMessage);
            // @ts-ignore: Custom event type supported by library but not in types definition
            this.eventSource.addEventListener('endpoint', onEndpoint);
            this.eventSource.addEventListener('error', onError);
        });

        return this.connectionPromise;
    }

    async disconnect(): Promise<void> {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        this.isConnected = false;
        this.endpoint = null;
        this.connectionPromise = null;

        // Reject all pending requests
        for (const [id, { reject }] of this.pendingRequests) {
            reject(new Error('Transport disconnected'));
        }
        this.pendingRequests.clear();
    }

    private handleIncomingMessage(data: JsonRpcResponse) {
        if (data.id && this.pendingRequests.has(data.id)) {
            const { resolve, reject } = this.pendingRequests.get(data.id)!;
            this.pendingRequests.delete(data.id);

            if (data.error) {
                reject(new Error(data.error.message));
            } else {
                resolve(data); // Return full response, caller extracts result
            }
        }
    }

    private async sendRequest(method: string, params: any): Promise<any> {
        if (!this.isConnected || !this.endpoint) {
            throw new Error('SSE Transport not connected or missing endpoint');
        }

        // 🔑 兼容性调整：将 ID 改为纯数字。
        // 部分 Python JSON-RPC 库（如 json-rpc 某些旧版本）对 String ID 处理可能存在 Bug，或者服务端期望 Number。
        const id = Date.now();

        // Construct full POST URL based on the endpoint received via SSE
        let postUrl: string;
        try {
            // Check if endpoint is absolute
            new URL(this.endpoint!);
            postUrl = this.endpoint!;
        } catch {
            // It's relative, join with baseUrl
            const baseObj = new URL(this.baseUrl);
            // 🔑 修复：强制将 baseUrl 的路径视为目录 (Directory-like)，避免标准 URL 解析将最后一节路径视为文件而丢弃
            // 例如：base=/mcp (no slash), endpoint=messages. standard -> /messages. fixed -> /mcp/messages.
            if (!baseObj.pathname.endsWith('/')) {
                baseObj.pathname += '/';
            }
            postUrl = new URL(this.endpoint!, baseObj).toString();
        }

        const body: JsonRpcRequest = {
            jsonrpc: '2.0',
            id,
            method,
            params
        };

        // 🔍 Debug Log: Inspect the exact payload being sent
        console.log(`[SseTransport] Sending Request to ${postUrl}`);
        console.log(`[SseTransport] Payload:`, JSON.stringify(body, null, 2));

        return new Promise(async (resolve, reject) => {
            // Register pending request BEFORE fetch to avoid race conditions if response is super fast (unlikely via SSE but safe)
            // Wait: Response comes via SSE stream, NOT fetch response body in MCP SSE!
            // The fetch response is usually just 202 Accepted.
            this.pendingRequests.set(id, { resolve, reject });

            try {
                const res = await fetch(postUrl, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(body)
                });

                if (!res.ok) {
                    this.pendingRequests.delete(id);
                    reject(new Error(`Failed to send request: ${res.statusText}`));
                }
            } catch (e) {
                this.pendingRequests.delete(id);
                reject(e);
            }
        });
    }

    async listTools(): Promise<McpTool[]> {
        const response = await this.sendRequest('tools/list', {});
        return this.extractTools(response);
    }

    async callTool(name: string, args: any): Promise<any> {
        const response = await this.sendRequest('tools/call', {
            name,
            arguments: args
        });
        return response.result;
    }

    private extractTools(data: any): McpTool[] {
        if (data.result && Array.isArray(data.result.tools)) {
            return data.result.tools;
        }
        if (Array.isArray(data.tools)) {
            return data.tools;
        }
        return [];
    }
}
