/**
 * LLM Output Patterns
 * Shared regex patterns for identifying structured blocks in LLM output.
 * Used by both StreamParser (for data extraction) and StreamingCardList (for display filtering).
 */

// Matches ANY structured block (Thinking, Tools, Plans, etc.)
// - Thinking: <!-- THINKING_START -->...<!-- THINKING_END --> OR <think>...</think> OR <thought>...</thought>
// - Tools: <tool_code>...</tool_code> OR <tool_calls>...</tool_calls> OR <tools>...</tools> OR <tool_call>...</tool_call> OR <call>...</call>
// - Plan: <plan>...</plan>
//
// NOTE: We use [^>]* to matching opening tags with attributes (like <call tool="search">)
// NOTE: We use [\s\S]*? for non-greedy matching across newlines
export const LLM_STRUCTURED_BLOCK_REGEX = /((?:<!--\s*THINKING_START\s*-->[\s\S]*?<!--\s*THINKING_END\s*-->)|(?:<(?:think|thought|plan|tool_code|tool_calls|tools|tool_call|call)[^>]*>[\s\S]*?<\/(?:think|thought|plan|tool_code|tool_calls|tools|tool_call|call)>))/gi;

// Heuristic to identify if a string STARTs with a structured tag.
// Used for filtering split results.
export const LLM_TAG_START_REGEX = /^<!--\s*THINKING_START|^<(?:think|thought|plan|tool_code|tool_calls|tools|tool_call|call)/i;
