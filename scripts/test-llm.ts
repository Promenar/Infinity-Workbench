console.log("来自 scripts/test-debug.ts 的问候");
import './test-setup';
console.log("已导入 test-setup");
import path from 'path';
console.log("已导入 path");
import { setupEnvironment, loadTestConfig, getActiveProvider } from './test-utils';
console.log("已导入 test-utils");
import { OpenAiClient } from '../src/lib/llm/providers/openai';
console.log("已导入 OpenAiClient");
import { VertexAiClient } from '../src/lib/llm/providers/vertexai';
console.log("已导入 VertexAiClient");
import { OpenAiCompatibleClient } from '../src/lib/llm/providers/openai-compatible';
console.log("已导入 OpenAiCompatibleClient");
import { LlmClient, ChatMessage } from '../src/lib/llm/types';
import { Skill } from '../src/types/skills';
console.log("已导入 types");

console.log("正在调用 setupEnvironment...");
setupEnvironment();
console.log("setupEnvironment 完成");

async function runTest() {
    console.log('\n🤖 \x1b[36m正在初始化 LLM 集成测试...\x1b[0m');

    // 2. 加载配置与提供商
    const config = loadTestConfig();
    const { provider, config: providerConfig } = getActiveProvider(config);

    console.log(`✅ 当前提供商: \x1b[33m${provider}\x1b[0m`);
    console.log(`✅ 模型 ID: \x1b[33m${providerConfig.modelId}\x1b[0m`);
    console.log(`✅ Base URL: \x1b[33m${providerConfig.baseUrl}\x1b[0m`);

    // 3. 创建客户端 (绕过 factory.ts 以避免原生依赖)
    let client: LlmClient;

    // 归一化提供商名称
    const providerType = provider === 'vertex-ai' ? 'google' : (provider === 'zhipu-ai' ? 'zhipu' : provider);

    if (providerType === 'google') {
        client = new VertexAiClient({
            apiKey: providerConfig.apiKey,
            model: providerConfig.modelId,
            temperature: 0.7,
            baseUrl: providerConfig.baseUrl || '',
            project: providerConfig.projectId,
            location: providerConfig.region,
            keyJson: providerConfig.keyFile
                ? JSON.stringify(require(path.resolve(process.cwd(), providerConfig.keyFile)))
                : undefined,
        });
    } else if (providerType === 'ollama' || providerType === 'openai-compatible') {
        client = new OpenAiCompatibleClient(
            providerConfig.apiKey,
            providerConfig.modelId,
            0.7,
            providerConfig.baseUrl
        );
    } else if (providerType === 'zhipu' || providerType === 'moonshot' || providerType === 'deepseek' || providerType === 'siliconflow' || providerType === 'newapi') {
        client = new OpenAiClient(
            providerConfig.apiKey,
            providerConfig.modelId,
            0.7,
            providerConfig.baseUrl,
            { provider: providerType }
        );
    } else {
        throw new Error(`测试脚本不支持的提供商类型: ${providerType}`);
    }

    // 4. 测试 1: 聊天补全 (流式)
    console.log('\n🧪 \x1b[36m测试 1: 聊天补全 (流式)...\x1b[0m');
    const messages: ChatMessage[] = [
        { role: 'user', content: 'Say "Hello World" and tell me a very short joke.' }
    ];

    let fullResponse = '';
    process.stdout.write('   响应: ');

    await client.streamChat(
        messages,
        (token) => {
            let text = '';
            if (typeof token === 'string') text = token;
            else if (token.content) text = token.content;

            process.stdout.write(text);
            fullResponse += text;
        },
        (err) => {
            console.error('\n❌ 流式传输错误:', err);
            process.exit(1);
        }
    );

    console.log('\n   ✅ 流式传输完成。');

    // 5. 测试 2: 工具调用 (Tool Calling)
    console.log('\n🧪 \x1b[36m测试 2: 工具调用验证...\x1b[0m');

    const toolMessages: ChatMessage[] = [
        { role: 'user', content: 'What is the current time in Tokyo?' }
    ];

    const mockTool: Skill = {
        id: 'get_current_time',
        name: 'get_current_time',
        description: 'Get the current time in a specific city',
        schema: {
            type: 'object',
            properties: {
                city: { type: 'string', description: 'The city name' }
            },
            required: ['city']
        } as any,
        execute: async () => ({ id: 'test', content: 'test', status: 'success' })
    };

    process.stdout.write('   正在发送包含工具的请求... ');

    try {
        let toolCallDetected = false;
        let toolCallData: any[] = [];

        await client.streamChat(
            toolMessages,
            (token) => {
                if (typeof token !== 'string' && token.toolCalls) {
                    toolCallDetected = true;
                    toolCallData = token.toolCalls;
                    process.stdout.write('🛠️ ');
                }
            },
            (err) => {
                throw err;
            },
            { skills: [mockTool] }
        );

        console.log('');
        if (toolCallDetected) {
            console.log('   ✅ 检测到工具调用!');
            console.log('   📦 工具数据:', JSON.stringify(toolCallData, null, 2));
        } else {
            console.log('   ⚠️ 未检测到工具调用。');
        }

    } catch (err: any) {
        console.error('\n❌ 工具测试错误:', err.message);
        if (err.response) console.error('   响应体:', err.response);
    }
}
runTest().catch(console.error);
