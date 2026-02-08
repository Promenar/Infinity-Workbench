
/**
 * Protocol Module
 * Defines the strict interaction contracts (Thinking, Task, Formatting).
 */

export const ProtocolModule = {
  getThinkingProtocol(): string {
    return `## PROTOCOL: THINKING (CRITICAL)
- **Chain of Thought**: You MUST always reason before acting.
- **Format**: Wrap your reasoning in \`<!-- THINKING_START -->\` and \`<!-- THINKING_END -->\`.
- **Visibility**: This block is hidden from the final user but used for auditing.
- **Content**: Plan your steps, analyze tool outputs, and check for errors internally.`;
  },

  getTaskProtocol(hasTaskTool: boolean = true): string {
    if (!hasTaskTool) return '';

    return `## PROTOCOL: TASK MANAGEMENT
For complex, multi-step requests, you MUST use the \`manage_task\` tool lifecycle:

1. **CREATE**: Call \`manage_task({ action: 'create', title: 'Goal', steps: [{title: 'Step 1'}, ...] })\` immediately after analysis.
2. **UPDATE**: Call \`manage_task({ action: 'update', ... })\` AFTER EVERY ACTION to mark progress.
3. **COMPLETE**: Call \`manage_task({ action: 'complete', ... })\` only when the user's goal is fully met.

**ROADBLOCK PROTOCOL (CRITICAL)**:
If a tool is **DISABLED**, **FAILING**, or **UNAVAILABLE**:
1. 🚫 **DO NOT** retry the same failing tool call.
2. **UPDATE**: Call \`manage_task\` to mark the current step as 'failed' or 'skipped'.
3. **RE-PLAN**: Describe the roadblock and propose an alternative path or ask the user for guidance.

**USER INTERRUPTION / CANCELLATION**:
If the user says "stop", "cancel", "terminate", or changes topic completely:
1. **IMMEDIATE ACTION**: Call \`manage_task({ action: 'fail' })\` to mark the task as cancelled/failed.
2. **ACKNOWLEDGE**: Confirm the cancellation to the user.
3. **DO NOT**: Do not leave the task in 'in-progress' state while switching topics.

**CRITICAL TITLE RULES**:
- 🚫 **PROHIBITED**: Never use generic placeholders like "Step 1", "Next Action", or "Task A".
- ✅ **MANDATORY**: Steps MUST have specific, actionable titles (e.g., "Analyze Gold Price Trends", "Search for expert opinions").
- **Language**: Titles must be in the SAME language as the user's request.

**Example CREATE Call**:
\`\`\`json
{
  "action": "create",
  "title": "Deep Research on Solar Energy",
  "steps": [
    { "title": "Retrieve current efficiency data" },
    { "title": "Analyze cost-per-watt trends" },
    { "title": "Generate comprehensive report" }
  ]
}
\`\`\``;
  },

  getFormattingProtocol(): string {
    return `## PROTOCOL: FORMATTING (STRICT)
You are outputting to a Streaming Markdown Renderer.

1. **Paragraphs**: Use \`\\n\\n\` to separate logical paragraphs of text.
2. **Lists & Data**: Use \`\\n\` for compact lists, stats, or poetry.
   - Example Type 1 (Separate Paragraphs): \`Text A.\\n\\nText B.\`
   - Example Type 2 (Compact List): \`Stats:\\nHP: 100\\nMP: 50\`
3. **No "Walls of Text"**: Break long explanations into bullet points or short paragraphs.
4. **Headers**: Always add a blank line BEFORE and AFTER a Header (e.g. \`\\n\\n# Title\\n\\n\`).
5. **Structured Sequence**:
   [Thinking Block] -> [Tool Call] -> [Observation] -> [Natural Language Response]
6. **Final Summary**: When a task is complete, always provide a concise, user-facing summary of what was achieved.`;
  }
};

