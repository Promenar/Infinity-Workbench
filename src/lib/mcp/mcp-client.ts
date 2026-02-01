import { McpServerConfig } from '../../store/mcp-store';
import { McpTool, McpTransport } from './transport';
import { HttpTransport } from './transports/http-transport';
import { SseTransport } from './transports/sse-transport';

export class McpClient {
    private transport: McpTransport;
    private config: McpServerConfig | { url: string; type?: 'http' | 'sse' };

    constructor(config: McpServerConfig | { url: string; type?: 'http' | 'sse' }) {
        this.config = config;

        // Default to HTTP if type not specified (backward compatibility)
        const type = config.type || 'http'; // Default to HTTP for safety during migration

        if (type === 'sse') {
            this.transport = new SseTransport(config.url);
        } else {
            this.transport = new HttpTransport(config.url);
        }
    }

    /**
     * 显式连接到服务器
     */
    async connect(): Promise<void> {
        return this.transport.connect();
    }

    /**
     * 初始化连接并获取工具
     */
    async listTools(): Promise<McpTool[]> {
        await this.connect(); // Ensure connected
        return this.transport.listTools();
    }

    /**
     * 调用工具
     */
    async callTool(name: string, args: any): Promise<any> {
        return this.transport.callTool(name, args);
    }

    /**
     * 断开连接
     */
    async disconnect(): Promise<void> {
        return this.transport.disconnect();
    }
}
