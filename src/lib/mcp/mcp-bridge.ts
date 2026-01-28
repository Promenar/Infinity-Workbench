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

        try {
            setServerStatus(server.id, 'loading');
            const client = new McpClient(server.url);
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
                        const result = await client.callTool(t.name, params);
                        return {
                            id: `mcp_exec_${Date.now()}`,
                            content: typeof result === 'string' ? result : JSON.stringify(result, null, 2),
                            status: 'success',
                            data: result
                        };
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
        }
    }

    /**
     * 简单的 JSON Schema 到 Zod 转换
     */
    private static jsonSchemaToZod(schema: any): z.ZodSchema<any> {
        return z.any().describe(JSON.stringify(schema));
    }
}
