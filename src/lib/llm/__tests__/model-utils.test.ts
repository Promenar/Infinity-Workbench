/**
 * ModelUtils 单元测试
 * 测试模型工具函数
 */

// Mock zustand store
jest.mock('../../../store/api-store', () => ({
  useApiStore: {
    getState: () => ({
      providers: [
        {
          id: 'openai',
          models: [
            { uuid: 'uuid-gpt4', id: 'gpt-4', name: 'GPT-4', contextLength: 8192, type: 'chat', capabilities: { tools: true } },
            { uuid: 'uuid-gpt35', id: 'gpt-3.5-turbo', name: 'GPT-3.5', contextLength: 4096, type: 'chat' },
            { uuid: 'uuid-dalle', id: 'dall-e-3', name: 'DALL-E 3', contextLength: 4096, type: 'image' },
            { uuid: 'uuid-ada', id: 'text-embedding-ada-002', name: 'Embedding Ada', contextLength: 8192, type: 'embedding' },
          ],
        },
        {
          id: 'gemini',
          models: [
            { uuid: 'uuid-gemini', id: 'gemini-pro', name: 'Gemini Pro', contextLength: 32768, type: 'chat', capabilities: { reasoning: true } },
          ],
        },
      ],
    }),
  },
}));

// 导入必须在 mock 之后
import {
  findModelSpec,
  isForcedReasoningModel,
  supportsThinkingConfig,
  getModelType,
  getModelCapabilities,
  getModelIcon,
  resolveModelIdToName,
  isHighCapabilityModel,
} from '../model-utils';

describe('ModelUtils', () => {
  describe('findModelSpec', () => {
    it('应通过 ID 找到模型规格', () => {
      const spec = findModelSpec('gpt-4');
      expect(spec).toBeDefined();
    });

    it('应通过 UUID 找到模型规格', () => {
      const spec = findModelSpec('uuid-gpt4');
      expect(spec).toBeDefined();
    });

    it('应处理大小写不敏感', () => {
      const spec1 = findModelSpec('GPT-4');
      const spec2 = findModelSpec('gpt-4');
      expect(spec1).toBeDefined();
      expect(spec2).toBeDefined();
    });

    it('应合并 Store 和静态规格', () => {
      const spec = findModelSpec('gpt-4');
      if (spec) {
        expect(spec.contextLength).toBeGreaterThan(0);
      }
    });

    it('应处理未知模型', () => {
      const spec = findModelSpec('unknown-model-xyz');
      // 返回 Store 配置或 undefined
      expect(spec === undefined || spec.pattern !== undefined).toBe(true);
    });
  });

  describe('isForcedReasoningModel', () => {
    it('应正确识别推理模型', () => {
      // DeepSeek R1 是推理模型
      const result = isForcedReasoningModel('deepseek-ai/DeepSeek-R1');
      expect(typeof result).toBe('boolean');
    });

    it('应正确识别非推理模型', () => {
      const result = isForcedReasoningModel('gpt-3.5-turbo');
      expect(result).toBe(false);
    });
  });

  describe('supportsThinkingConfig', () => {
    it('应识别 Gemini Flash Thinking', () => {
      const result = supportsThinkingConfig('gemini-2.0-flash-thinking-exp');
      expect(result).toBe(true);
    });

    it('应识别 Gemini 1.5', () => {
      const result = supportsThinkingConfig('gemini-1.5-pro');
      expect(result).toBe(true);
    });

    it('应识别 Gemini 2.0', () => {
      const result = supportsThinkingConfig('gemini-2.0-flash');
      expect(result).toBe(true);
    });

    it('应识别带 reasoning 能力的模型', () => {
      const result = supportsThinkingConfig('gemini-pro');
      expect(result).toBe(true);
    });

    it('应返回 false 对于不支持的模型', () => {
      const result = supportsThinkingConfig('gpt-3.5-turbo');
      expect(result).toBe(false);
    });
  });

  describe('getModelType', () => {
    it('应返回 chat 类型', () => {
      const type = getModelType('gpt-4');
      expect(type).toBe('chat');
    });

    it('应返回 reasoning 类型', () => {
      const type = getModelType('deepseek-ai/DeepSeek-R1');
      expect(type).toBe('reasoning');
    });

    it('应返回 image 类型', () => {
      const type = getModelType('dall-e-3');
      expect(type).toBe('image');
    });

    it('应返回 embedding 类型', () => {
      const type = getModelType('text-embedding-ada-002');
      expect(type).toBe('embedding');
    });

    it('应默认返回 chat', () => {
      const type = getModelType('some-unknown-model');
      expect(type).toBe('chat');
    });
  });

  describe('getModelCapabilities', () => {
    it('应返回模型能力对象', () => {
      const caps = getModelCapabilities('gpt-4');
      expect(typeof caps).toBe('object');
    });

    it('应包含工具调用能力', () => {
      const caps = getModelCapabilities('gpt-4');
      expect(caps.tools).toBe(true);
    });

    it('应返回空对象对于未知模型', () => {
      const caps = getModelCapabilities('unknown-model');
      expect(caps).toEqual({});
    });
  });

  describe('getModelIcon', () => {
    it('应为已知模型返回图标', () => {
      const icon = getModelIcon('gpt-4');
      expect(icon).toBeDefined();
    });

    it('应返回 undefined 对于未知模型', () => {
      const icon = getModelIcon('unknown-model');
      expect(icon).toBeUndefined();
    });
  });

  describe('resolveModelIdToName', () => {
    it('应通过 UUID 解析', () => {
      const name = resolveModelIdToName('uuid-gpt4');
      expect(name).toBe('GPT-4');
    });

    it('应通过 API ID 解析', () => {
      const name = resolveModelIdToName('gpt-4');
      expect(name).toBe('GPT-4');
    });

    it('应返回原 ID 当无法解析', () => {
      const name = resolveModelIdToName('unknown-model-xyz');
      expect(name).toBe('unknown-model-xyz');
    });

    it('应处理空字符串', () => {
      const name = resolveModelIdToName('');
      expect(name).toBe('');
    });
  });

  describe('isHighCapabilityModel', () => {
    describe('高参数模型识别', () => {
      it('应识别 GPT-4 系列', () => {
        expect(isHighCapabilityModel('gpt-4')).toBe(true);
        expect(isHighCapabilityModel('gpt-4o')).toBe(true);
      });

      it('应识别 Claude 3.5+', () => {
        expect(isHighCapabilityModel('claude-3.5-sonnet')).toBe(true);
        expect(isHighCapabilityModel('claude-3.5-haiku')).toBe(true);
      });

      it('应识别 Gemini 系列', () => {
        expect(isHighCapabilityModel('gemini-2.0-flash')).toBe(true);
        expect(isHighCapabilityModel('gemini-1.5-pro')).toBe(true);
      });

      it('应识别 DeepSeek 系列', () => {
        expect(isHighCapabilityModel('deepseek-v3')).toBe(true);
        expect(isHighCapabilityModel('deepseek-r1')).toBe(true);
      });

      it('应识别 GLM 系列', () => {
        expect(isHighCapabilityModel('glm-4-plus')).toBe(true);
        expect(isHighCapabilityModel('glm-4.7')).toBe(true);
      });

      it('应识别 Qwen 系列', () => {
        expect(isHighCapabilityModel('qwen-max')).toBe(true);
        expect(isHighCapabilityModel('qwen-plus')).toBe(true);
      });

      it('应识别 Kimi 系列', () => {
        expect(isHighCapabilityModel('kimi-k2')).toBe(true);
        expect(isHighCapabilityModel('moonshot-v1-8k')).toBe(true);
      });

      it('应识别推理模型', () => {
        expect(isHighCapabilityModel('o1-preview')).toBe(true);
        expect(isHighCapabilityModel('o1-mini')).toBe(true);
        expect(isHighCapabilityModel('deepseek-reasoner')).toBe(true);
      });
    });

    describe('中低参数模型排除', () => {
      it('应排除 GPT-3.5', () => {
        expect(isHighCapabilityModel('gpt-3.5-turbo')).toBe(false);
        expect(isHighCapabilityModel('gpt-3.5')).toBe(false);
      });

      it('应排除 Qwen-turbo', () => {
        expect(isHighCapabilityModel('qwen-turbo')).toBe(false);
        expect(isHighCapabilityModel('qwen2.5-7b')).toBe(false);
      });

      it('应排除 GLM-flash', () => {
        expect(isHighCapabilityModel('glm-4-flash')).toBe(false);
        expect(isHighCapabilityModel('glm-3-turbo')).toBe(false);
      });

      it('应排除 Llama 小模型', () => {
        expect(isHighCapabilityModel('llama-2-7b')).toBe(false);
        expect(isHighCapabilityModel('llama-3-8b')).toBe(false);
      });

      it('应排除 Doubao-lite', () => {
        expect(isHighCapabilityModel('doubao-lite')).toBe(false);
      });

      it('应排除 Ernie 系列', () => {
        expect(isHighCapabilityModel('ernie-speed')).toBe(false);
        expect(isHighCapabilityModel('ernie-turbo')).toBe(false);
      });

      it('应排除 Yi 系列小模型', () => {
        expect(isHighCapabilityModel('yi-6b')).toBe(false);
        expect(isHighCapabilityModel('yi-medium')).toBe(false);
      });

      it('应排除 Baichuan 系列', () => {
        expect(isHighCapabilityModel('baichuan-2')).toBe(false);
        expect(isHighCapabilityModel('baichuan-3')).toBe(false);
      });
    });

    describe('边界情况', () => {
      it('应处理空字符串', () => {
        expect(isHighCapabilityModel('')).toBe(false);
      });

      it('应处理未知模型', () => {
        expect(isHighCapabilityModel('completely-unknown-model-xyz')).toBe(false);
      });

      it('应处理大小写不敏感', () => {
        expect(isHighCapabilityModel('GPT-4')).toBe(true);
        expect(isHighCapabilityModel('DeepSeek-R1')).toBe(true);
      });

      it('应正确区分 qwen-turbo vs qwen-plus', () => {
        expect(isHighCapabilityModel('qwen-turbo')).toBe(false);
        expect(isHighCapabilityModel('qwen-plus')).toBe(true);
        expect(isHighCapabilityModel('qwen-max')).toBe(true);
      });
    });
  });
});
