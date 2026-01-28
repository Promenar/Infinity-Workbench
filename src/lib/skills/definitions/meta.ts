import { z } from 'zod';
import { Skill, SkillContext } from '../../../types/skills';
import { UserSkillsStorage } from '../storage';
import { skillRegistry } from '../registry';

/**
 * 元工具：工具管理器 (Tool Manager)
 * 允许 AI 动态创建、测试并注册新工具。
 */
export const ToolManagerSkill: Skill = {
    id: 'tool_manager',
    name: 'Tool Manager (Meta-Tool)',
    description: `Create, Test, and Register NEW Tools.
⚠️ STRICT SAFETY & PROCESS RULES:
1. **PRE-PLANNING REQUIRED**: You MUST use the \`manage_task\` tool to create a comprehensive plan (Draft -> Test -> Register) BEFORE doing anything.
2. **USER CONSENT MANDATORY**: Before executing the creation or test code, you MUST pause and ask the user for permission.
   - **HOW**: Call \`manage_task\` with \`{ action: 'ask_user', question: '...' }\`.
   - **DO NOT** just print the question in the output. You MUST use the tool to trigger the input UI.
   - explain WHY this tool is needed and HOW it will verify its safety.
3. **NO CODE IN CHAT**: Do NOT output raw code blocks or task drafts in your conversation response.
   - If you need to store a draft, use the \`write_file\` tool to save it to the workspace.
   - Keep implementation details in your thought process (Chain of Thought).
   - Only describe the tool's purpose and status to the user.
4. **SELF-TESTING**: You must define a test case and execute it in the sandbox (action: 'test') before registering.

Actions:
- 'test': Run code in a sandbox to verify logic.
- 'register': Save the tool to the user's library.
- 'delete': Remove a user-created tool.
- 'list': List all user-created tools.

4. **CAPABILITY BOUNDARIES**:
   - **Environment**: Runs in a restricted JS sandbox. NO \`require()\`, NO \`import\`, NO DOM access.
   - **Network**: **ALLOWED**. You CAN use the standard \`fetch\` API to make HTTP requests.
   - **State**: Stateless. Global variables do not persist between calls.
   - **Output**: Must return a serializable JSON object or string. CANNOT return React components or UI elements.

5. **BEST PRACTICES**:
   - **Good Candidates**: API Integrations (Weather, Stock, News), Data Transformation, Logic Puzzles.
   - **Bad Candidates**: UI rendering, File I/O (Use \`write_file\`), CPU intensive tasks.`,
    isHighRisk: true, // 🚨 元修改属于高风险操作
    category: 'preset',
    schema: z.object({
        action: z.enum(['test', 'register', 'delete', 'list']).describe('要执行的操作 (Action)'),
        // For 'test' and 'register'
        name: z.string().optional().describe('工具名称 (例如 "Calculate Tax")'),
        id: z.string().optional().describe('工具 ID (例如 "calc_tax")。必须唯一。'),
        description: z.string().optional().describe('给 LLM 看的工具描述'),
        code: z.string().optional().describe('要执行的 JavaScript 代码。必须以 return 语句结束或为函数体。可用全局变量: console, params。'),
        schema: z.string().optional().describe('工具参数的 JSON Schema 定义 (字符串格式)。'),

        // For 'test' only
        testParams: z.any().optional().describe('用于测试的代码参数'),

        // For 'register' only
        isHighRisk: z.boolean().optional().describe('如果是高风险操作（如修改文件、访问网络或泄漏数据），请标记为 True。'),

        // For 'delete'
        targetId: z.string().optional().describe('要删除的工具 ID')
    }),
    execute: async (params, context) => {
        const { action } = params;

        if (action === 'list') {
            const skills = await UserSkillsStorage.loadSkills();
            return {
                id: `list_${Date.now()}`,
                content: skills.length > 0
                    ? `Found ${skills.length} user skills:\n${skills.map(s => `- ${s.name} (${s.id})`).join('\n')}`
                    : 'No user-created skills found.',
                status: 'success',
                data: skills
            };
        }

        if (action === 'delete') {
            if (!params.targetId) throw new Error('targetId is required for delete action');
            await UserSkillsStorage.deleteSkill(params.targetId);
            await skillRegistry.reloadUserSkills();
            return {
                id: `del_${Date.now()}`,
                content: `Successfully deleted tool: ${params.targetId}`,
                status: 'success'
            };
        }

        if (action === 'test') {
            if (!params.code) throw new Error('Code is required for testing');

            // 创建一个临时的“已水合”技能来运行代码
            const tempSkillData = {
                id: 'temp_test',
                name: 'Test',
                description: 'Test',
                code: params.code,
                schemaJson: params.schema || '{}',
                createdAt: Date.now(),
                updatedAt: Date.now()
            };

            const tempSkill = UserSkillsStorage.hydrateSkill(tempSkillData);
            const testResult = await tempSkill.execute(params.testParams || {}, context);

            return {
                id: `test_${Date.now()}`,
                content: `🧪 Test Execution Result:\n${testResult.content}`,
                status: testResult.status === 'success' ? 'success' : 'error',
                data: testResult.data
            };
        }

        if (action === 'register') {
            if (!params.id || !params.name || !params.description || !params.code || !params.schema) {
                throw new Error('Missing required fields for registration: id, name, description, code, schema');
            }

            // 保存到存储
            await UserSkillsStorage.saveSkill({
                id: params.id,
                name: params.name,
                description: params.description,
                code: params.code,
                schemaJson: params.schema,
                isHighRisk: params.isHighRisk || false,
                category: 'model', // 默认情况下，模型创建的工具归类为 'model'
                author: 'model',
                createdAt: Date.now(),
                updatedAt: Date.now()
            });

            // 重载注册表
            await skillRegistry.reloadUserSkills();

            return {
                id: `reg_${Date.now()}`,
                content: `✅ Successfully registered tool: "${params.name}" (${params.id}).\nYou can now call this tool in future turns.`,
                status: 'success'
            };
        }

        return {
            id: 'error',
            content: `Unknown action: ${action}`,
            status: 'error'
        };
    }
};
