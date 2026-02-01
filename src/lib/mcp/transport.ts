
export interface McpTool {
    name: string;
    description: string;
    inputSchema: any;
}

export interface McpTransport {
    connect(): Promise<void>;
    disconnect(): Promise<void>;
    listTools(): Promise<McpTool[]>;
    callTool(name: string, args: any): Promise<any>;
}
