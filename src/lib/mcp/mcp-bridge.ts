import { skillRegistry } from '../skills/registry';
import { useMcpStore } from '../../store/mcp-store';
import { McpClient } from './mcp-client';
import { Skill } from '../../types/skills';
import { z } from 'zod';

/**
 * MCP 桥接器：负责同步外部工具到本地注册表
 */
export class McpBridge {
    /**
     * 同步所有已启用的 MCP 服务器工具
     */
    static async syncAll() {
        const { servers } = useMcpStore.getState();

        // 🔑 增强：清理那些现在已禁用或不存在的服务器工具
        const registeredMcpServerIds = new Set(
            skillRegistry.getAllSkills()
                .map(s => s.mcpServerId)
                .filter(Boolean)
        );

        for (const serverId of registeredMcpServerIds) {
            const server = servers.find(s => s.id === serverId);
            if (!server || !server.enabled) {
                skillRegistry.removeSkillsByServer(serverId as string);
            }
        }

        const enabledServers = servers.filter(s => s.enabled);
        console.log(`[McpBridge] Starting sync for ${enabledServers.length} servers...`);

        for (const server of enabledServers) {
            await this.syncServer(server.id);
        }
    }

    /**
     * 同步单个服务器
     */
    static async syncServer(serverId: string) {
        const { servers, setServerStatus } = useMcpStore.getState();
        const server = servers.find(s => s.id === serverId);
        if (!server) return;

        // 如果服务器被禁用，直接移除工具并返回
        if (!server.enabled) {
            skillRegistry.removeSkillsByServer(serverId);
            setServerStatus(serverId, 'disconnected');
            return;
        }

        let client: McpClient | null = null;
        try {
            setServerStatus(server.id, 'loading');
            client = new McpClient(server);
            const tools = await client.listTools();

            // 🔑 覆盖式同步：同步前先清理该服务器的旧工具
            skillRegistry.removeSkillsByServer(server.id);

            // 将 MCP 工具转换为本地 Skill 对象
            const skills: Skill[] = tools.map(t => {
                // 针对 Alpha Vantage 等暴露元工具的服务器进行描述增强
                let description = `[MCP: ${server.name}] ${t.description}`;
                if (t.name === 'TOOL_LIST') description = `[元工具] 列出 ${server.name} 的所有可用接口`;
                if (t.name === 'TOOL_GET') description = `[元工具] 获取 ${server.name} 接口的详细参数定义`;
                if (t.name === 'TOOL_CALL') description = `[元工具] 调用 ${server.name} 的指定金融接口 (如查询股价)`;

                return {
                    id: `mcp_${server.id}_${t.name}`,
                    name: t.name,
                    description: description,
                    category: 'model',
                    mcpServerId: server.id,
                    author: `mcp:${server.id}`,
                    schema: McpBridge.jsonSchemaToZod(t.inputSchema),
                    execute: async (params, context) => {
                        // 🔑 框架增强：通用参数强制转换 (Schema-driven Coercion)
                        // 遍历 Schema 定义，如果某字段要求 string 但传入了 object，自动序列化
                        const processedParams = { ...params };
                        const inputSchema = t.inputSchema || {};

                        if (inputSchema.properties) {
                            for (const [key, value] of Object.entries(inputSchema.properties as Record<string, any>)) {
                                const currentParam = processedParams[key];
                                if (value.type === 'string' && typeof currentParam === 'object' && currentParam !== null) {
                                    console.log(`[McpBridge] Coercing param '${key}' from object to string for tool '${t.name}'`);
                                    processedParams[key] = JSON.stringify(currentParam);
                                }
                            }
                        }

                        // 为了调用工具，我们需要重新创建客户端（因为 SSE 可能已断开）或者保持连接
                        // 对于简单的原子执行，建立即时连接是可接受的
                        const executionClient = new McpClient(server);
                        try {
                            // 🔑 优化：建立连接执行工具调用，并在完成后立即断开
                            // 这是一个无状态的执行模式
                            await executionClient.connect();
                            const result = await executionClient.callTool(t.name, processedParams);
                            return {
                                id: `mcp_exec_${Date.now()}`,
                                content: typeof result === 'string' ? result : JSON.stringify(result, null, 2),
                                status: 'success',
                                data: result
                            };
                        } finally {
                            await executionClient.disconnect();
                        }
                    }
                };
            });

            // 注册到全局注册表
            skills.forEach(s => skillRegistry.register(s));

            setServerStatus(server.id, 'connected');
            console.log(`[McpBridge] Synced ${tools.length} tools from ${server.name}`);
        } catch (error: any) {
            setServerStatus(server.id, 'error', error.message);
            console.error(`[McpBridge] Failed to sync ${server.name}:`, error);
        } finally {
            if (client) {
                await client.disconnect();
            }
        }
    }

    /**
     * 简单的 JSON Schema 到 Zod 转换
     * 🔑 增强：实现递归转换，支持嵌套对象、数组和基础类型描述
     */
    private static jsonSchemaToZod(schema: any): z.ZodSchema<any> {
        if (!schema || typeof schema !== 'object') return z.any();

        // 递归处理逻辑
        const convert = (s: any): z.ZodSchema<any> => {
            if (!s || typeof s !== 'object') return z.any();

            const description = s.description;
            let zodSchema: z.ZodSchema<any>;

            switch (s.type) {
                case 'string':
                    if (s.enum && Array.isArray(s.enum) && s.enum.length > 0) {
                        zodSchema = z.enum(s.enum as [string, ...string[]]);
                    } else {
                        zodSchema = z.string();
                    }
                    break;
                case 'number':
                case 'integer':
                    zodSchema = z.number();
                    break;
                case 'boolean':
                    zodSchema = z.boolean();
                    break;
                case 'array':
                    zodSchema = z.array(convert(s.items));
                    break;
                case 'object':
                    if (s.properties) {
                        const shape: Record<string, z.ZodSchema<any>> = {};
                        const required = s.required || [];

                        for (const [key, prop] of Object.entries(s.properties)) {
                            let propSchema = convert(prop);
                            if (!required.includes(key)) {
                                propSchema = propSchema.optional();
                            }
                            shape[key] = propSchema;
                        }
                        zodSchema = z.object(shape);
                    } else {
                        zodSchema = z.record(z.string(), z.any());
                    }
                    if (s.additionalProperties === false && (zodSchema as any).strict) {
                        zodSchema = (zodSchema as any).strict();
                    }
                    break;
                default:
                    zodSchema = z.any();
            }

            if (description && (zodSchema as any).describe) {
                zodSchema = (zodSchema as any).describe(description);
            }

            return zodSchema;
        };

        try {
            return convert(schema);
        } catch (e) {
            console.warn('[McpBridge] Zod conversion failed, falling back to any:', e);
            return z.any().describe(JSON.stringify(schema));
        }
    }
}
