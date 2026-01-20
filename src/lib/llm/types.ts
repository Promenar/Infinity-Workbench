export type ChatMessageContent =
  | string
  | Array<{ type: 'text'; text: string } | { type: 'image_url'; image_url: { url: string } }>;

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: ChatMessageContent;
  reasoning?: string; // Persistent reasoning content
  name?: string; // For tool/function responses (Gemini style)
  tool_call_id?: string; // For tool responses (OpenAI style)
  tool_calls?: any[]; // For assistant messages containing tool calls
  thought_signature?: string; // For Gemini 2.0 Thinking models
}

export interface StreamChunk {
  content: string;
  reasoning?: string;
  citations?: { title: string; url: string; source?: string }[];
  done: boolean;
}

export interface ChatMessageOptions {
  webSearch?: boolean;
  reasoning?: boolean;
  skills?: any[]; // Skill[] (avoid circular dependency for now, or use mapped type)
  inferenceParams?: {
    temperature?: number;
    topP?: number;
    maxTokens?: number;
    frequencyPenalty?: number;
    presencePenalty?: number;
    thinkingLevel?: 'low' | 'medium' | 'high' | 'minimal';
  };
}

export interface CompletionOptions {
  prompt: string;
  suffix?: string;
  maxTokens?: number;
  temperature?: number;
  stop?: string[];
}

export interface LlmClient {
  streamChat(
    messages: ChatMessage[],
    onChunk: (chunk: {
      content: string;
      reasoning?: string;
      thought_signature?: string;
      citations?: { title: string; url: string; source?: string }[];
      toolCalls?: any[]; // ToolCall[]
      usage?: { input: number; output: number; total: number };
    }) => void,
    onError: (err: Error) => void,
    options?: ChatMessageOptions,
  ): Promise<void>;

  /**
   * 非流式对话，用于工具调用或后台任务
   * @param messages 消息历史
   * @param options 选项
   * @returns 完整回复内容
   */
  chatCompletion(
    messages: ChatMessage[],
    options?: any,
  ): Promise<{ content: string; toolCalls?: any[]; usage?: { input: number; output: number; total: number } }>;

  generateImage?(
    prompt: string,
    options?: { size?: string; style?: string; quality?: string },
  ): Promise<{ url: string; revisedPrompt?: string }>;

  complete?(
    options: CompletionOptions,
  ): Promise<{ content: string; usage?: { input: number; output: number; total: number } }>;

  testConnection(): Promise<{ success: boolean; latency: number; error?: string }>;
  testRerankConnection?(): Promise<{ success: boolean; latency: number; error?: string }>;
  abort?(): void;
}
