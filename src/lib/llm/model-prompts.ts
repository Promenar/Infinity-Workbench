/**
 * 模型提示词工厂
 * 为不同模型家族提供定制化的隐式提示词
 * 
 * 设计原则：
 * - DeepSeek/Qwen：结构化 Markdown + 简洁中文指令 (Reasoning/Coder 优化)
 * - Moonshot/GLM：详细中文指令 + 角色扮演 (Long Context 优化)
 * - Gemini/Claude：详细英文指令 + XML 结构
 * - OpenAI：标准英文指令
 * - 本地模型：最简指令
 */

export type ModelFamily = 'gemini' | 'deepseek' | 'deepseek-reasoner' | 'glm' | 'moonshot' | 'qwen' | 'openai' | 'anthropic' | 'local' | 'unknown';

/**
 * 从 provider type 或模型名称推断模型家族
 */
export function inferModelFamily(providerType: string, modelName?: string): ModelFamily {
    const type = providerType.toLowerCase();
    const name = (modelName || '').toLowerCase();

    // Special case for DeepSeek Reasoner
    if (name.includes('reasoner') || name.includes('r1')) {
        if (type === 'deepseek' || name.includes('deepseek')) return 'deepseek-reasoner';
    }

    // 1. 精确匹配 provider type
    if (type === 'gemini' || type === 'google' || type === 'vertex' || type === 'vertexai') return 'gemini';
    if (type === 'deepseek') return 'deepseek';
    if (type === 'glm' || type === 'zhipu' || type === 'bigmodel') return 'glm';
    if (type === 'moonshot' || type === 'kimi') return 'moonshot';
    if (type === 'qwen' || type === 'alibaba' || type === 'dashscope') return 'qwen';
    if (type === 'openai' || type === 'azure') return 'openai';
    if (type === 'anthropic' || type === 'claude') return 'anthropic';
    if (type === 'ollama' || type === 'local' || type === 'lmstudio') return 'local';

    // 2. 从模型名称推断 (Fuzzy Match)
    if (name.includes('gemini') || name.includes('palm')) return 'gemini';
    if (name.includes('deepseek')) return 'deepseek';
    if (name.includes('glm') || name.includes('chatglm')) return 'glm';
    if (name.includes('moonshot') || name.includes('kimi')) return 'moonshot';
    if (name.includes('qwen') || name.includes('qwq')) return 'qwen';
    if (name.includes('gpt') || name.includes('o1') || name.includes('o3')) return 'openai';
    if (name.includes('claude')) return 'anthropic';
    if (name.includes('llama') || name.includes('mistral') || name.includes('mixtral')) return 'local';

    return 'unknown';
}

/**
 * 获取续杯提示词
 */
export function getContinuationPrompt(family: ModelFamily): string {
    switch (family) {
        case 'deepseek-reasoner':
            return `[SYSTEM UPDATE]: The user has approved continuation.
IMPORTANT:
1. REVIEW the conversation history above. The previous tool execution (if any) is likely ALREADY COMPLETED and the result is in the history.
2. DO NOT REPEAT the last step if the result is already available.
3. If the task state says "In Progress" but you see the tool output, trust the output and move to the NEXT step.
4. Continue logical execution immediately.`;

        case 'deepseek':
        case 'qwen':
            // 结构化 Markdown 指令
            return `### 系统指令：继续执行
用户已批准继续。
1. **优先回顾历史**：请检查对话历史中**最近一次工具调用的结果**。
2. **状态校准**：如果上一步操作已成功（如文件已写入），请立即调用 \`manage_task\` 更新进度或完成任务。
3. **禁止回溯**：如果数据已在历史记录中，**禁止**再次查询或搜索。
4. **继续推进**：直接输出下一步动作。`;

        case 'moonshot':
        case 'glm':
            // 详细自然语言指令
            return `[系统指令] 用户已批准继续执行任务。
请严格检查对话历史：
1. **核对断点**：确认上一步操作的结果（如文件写入是否成功，搜索是否有返回）。
2. **禁止重复**：如果历史中已有执行结果，**禁止**再次重复相同的工具调用。
3. **推进逻辑**：直接从断点处开始剩余工作，不要重新规划已完成的部分。`;

        case 'gemini':
        case 'openai':
        case 'anthropic':
            return `[SYSTEM: User approved continuation. Continue executing the CURRENT task from where you left off. Do NOT create a new task or restart. Focus on the REMAINING steps only.]`;

        case 'local':
            return `[Continue task. Do not restart.]`;

        default:
            return `[SYSTEM: Continue executing the current task. Do not restart.]`;
    }
}

/**
 * 获取任务规划指导
 */
export function getTaskPlanningGuidance(family: ModelFamily): string {
    switch (family) {
        case 'deepseek':
        case 'qwen':
            return `## 任务管理规范
对于复杂或多步骤任务，**必须**使用 \`manage_task\` 工具：

1. **创建计划 (Create)**: \`manage_task({ action: 'create', title: '任务名', steps: [...] })\`
2. **更新状态 (Update)**: 每完成一步，立即调用 \`manage_task({ action: 'update', ... })\`
3. **完成任务 (Complete)**: 全部结束后调用 \`manage_task({ action: 'complete' })\`

**严格要求**:
- 步骤标题必须**具体且具有描述性**（例："读取 user.ts 文件内容" ✅，"步骤1" ❌）
- 任务完成后，**必须**输出一段总结，说明核心成果。`;

        case 'moonshot':
        case 'glm':
            return `[任务规划指南]
当处理复杂问题时，请扮演一个严谨的项目经理，使用 \`manage_task\` 工具规划工作流：
- 首先创建详细的执行计划（Create）
- 在执行过程中实时更新步骤状态（Update）。**注意**：在更新下一步前，必须确认上一步的返回结果已生效。
- **禁止回溯**：如果一个步骤在历史记录中显示已成功，严禁重复执行。
- 只有所有工作都确认无误后才标记完成（Complete）

⚠️ 关键细节：
- 请确保每个步骤的标题都能清晰反映该步骤的具体内容（如"分析日志文件错误"）。
- 在任务结束时，请向用户汇报任务总结，涵盖已完成的工作和关键发现。`;

        case 'gemini':
        case 'openai':
        case 'anthropic':
            return `[PLANNING & TASK MANAGEMENT]
For complex, multi-step requests, you MUST use the \`manage_task\` tool:
- CREATE: \`manage_task({ action: 'create', title: '...', steps: [...] })\`
- UPDATE: After each step, call \`manage_task({ action: 'update', steps: [{ id: '...', status: 'completed' }] })\`
- COMPLETE: When all done, call \`manage_task({ action: 'complete' })\`

⚠️ CRITICAL:
- Each step MUST have a DESCRIPTIVE title (e.g., "Query weather for Nanjing tomorrow", NOT "Step 1")
- After completing ALL steps, you MUST provide a summary paragraph explaining what was accomplished`;

        case 'local':
            return `[TASK MANAGEMENT]
Use manage_task tool for multi-step tasks:
1. Create plan first
2. Update step status after each action
3. Complete when done
4. Always output a summary at the end`;

        default:
            return `[TASK MANAGEMENT]
Use manage_task for complex tasks. Each step needs a descriptive title. Output a summary when done.`;
    }
}

/**
 * 获取工具调用规范
 */
export function getToolUsageGuidance(family: ModelFamily, hasNativeSearch: boolean = false): string {
    const searchNoteChinese = hasNativeSearch
        ? '使用内置的原生搜索能力获取最新信息，**不要**调用 search_internet 工具。'
        : '需要外部信息时，请优先调用 query_vector_db 或 search_internet。';

    const searchNoteEnglish = hasNativeSearch
        ? 'Use your native search capability for current information. DO NOT call search_internet.'
        : 'Call query_vector_db or search_internet when you need information.';

    switch (family) {
        case 'deepseek':
        case 'qwen':
            return `## 工具调用规范
1. **直接调用**: 使用原生函数调用格式，不要输出 "我将调用..." 等废话。
2. **搜索策略**: ${searchNoteChinese}
3. **参数完整**: 确保所有必需参数都已填入。`;

        case 'moonshot':
        case 'glm':
            return `[工具使用规则]
1. 请直接使用 Function Calling 机制执行操作，无需口头描述计划。
2. ${searchNoteChinese}
3. 遇到问题时，可以灵活组合多个工具来解决。`;

        case 'gemini':
        case 'openai':
        case 'anthropic':
            return `[TOOL USAGE RULES]
1. Use native function calling. DO NOT write code blocks.
2. ${searchNoteEnglish}
3. DO NOT say "I will search..." - just call the function.
4. You may call multiple tools if needed.`;

        case 'local':
            return `[TOOLS]
Call tools directly. No explanations needed. ${searchNoteEnglish}`;

        default:
            return `[TOOLS]
Use function calling. ${searchNoteEnglish}`;
    }
}

/**
 * 获取输出格式指导
 */
export function getOutputFormatGuidance(family: ModelFamily): string {
    switch (family) {
        case 'deepseek':
        case 'qwen':
        case 'moonshot':
        case 'glm':
            return `[输出格式要求]
- **思考过程**（如果需要）: 包裹在 \`<!-- THINKING_START -->\` 和 \`<!-- THINKING_END -->\` 之间
- **最终回复**: 清晰、用户友好的自然语言，不包含思考标记
- **任务总结**: 多步任务完成后，必须包含一段核心成果总结
- **渲染能力增强**:
  - **流程图**: 使用 \`\`\`mermaid 代码块
  - **交互图表**: **必须** 使用 \`\`\`echarts 代码块 (内容为纯 JSON 配置)。
    - ❌ **仅在需要绘制图表时**，严禁使用 HTML / JS / Python 生成图片。
    - ✅ **允许** 在其他场景（如网页开发任务）中生成常规 HTML 代码。
  - **代码**: 支持语法高亮，请注明语言类型 (如 \`\`\`python)`;

        case 'gemini':
        case 'openai':
        case 'anthropic':
            return `[OUTPUT FORMAT]
- Wrap reasoning in <!-- THINKING_START --> and <!-- THINKING_END -->
- Final answers should be clean, user-facing text
- After completing a multi-step task, output a summary:
  1. What was accomplished
  2. Key results or findings
  3. Relevant details for the user
- **Rendering Capabilities**:
  - **Diagrams**: Use \`\`\`mermaid code blocks
  - **Charts**: Use \`\`\`echarts code blocks (content must be a valid JSON option object)
  - **Code**: Syntax highlighting is supported, always specify language (e.g., \`\`\`javascript)`;

        default:
            return '';
    }
}

/**
 * 获取完整的模型特定 System Prompt 增强
 */
export function getModelSpecificEnhancements(
    providerType: string,
    modelName?: string,
    options?: {
        hasNativeSearch?: boolean;
        hasTools?: boolean;
    }
): string {
    const family = inferModelFamily(providerType, modelName);
    const parts: string[] = [];

    if (options?.hasTools) {
        parts.push(getTaskPlanningGuidance(family));
        parts.push(getToolUsageGuidance(family, options?.hasNativeSearch));
    }

    const outputGuidance = getOutputFormatGuidance(family);
    if (outputGuidance) {
        parts.push(outputGuidance);
    }

    return parts.join('\n\n');
}
