import { PromptBuilder } from './assembler';
import { IdentityModule } from './modules/identity';
import { CapabilityModule } from './modules/capabilities';
import { ProtocolModule } from './modules/protocols';
import { getPromptLang, type PromptLang } from './i18n';

/**
 * 主装配函数
 * 替代旧的 "getModelSpecificEnhancements" 意面代码。
 * 🌐 I18N (2026-02-11): 新增 lang 参数支持动态语言切换。
 */
export function assembleSystemPrompt(
    modelFamily: string,
    options: {
        hasTools?: boolean;
        hasNativeSearch?: boolean;
        customPersona?: string;
        lang?: PromptLang;
    } = {}
): string {
    const builder = new PromptBuilder();
    const lang = options.lang || getPromptLang();

    // 1. Identity (Kernel + Persona)
    builder.addModule(IdentityModule.getKernelIdentity(lang));
    builder.addModule(IdentityModule.getPersona(options.customPersona, lang));

    // 2. Capability
    if (options.hasTools) {
        builder.addModule(CapabilityModule.getToolPhilosophy(options.hasNativeSearch, lang));
    }
    builder.addModule(CapabilityModule.getRendererCapabilities(lang));
    builder.addModule(CapabilityModule.getKnowledgeContext(lang));

    // 3. Protocol
    builder.addModule(ProtocolModule.getThinkingProtocol(lang));

    // 仅在启用工具时注入任务协议（意味着复杂任务）
    if (options.hasTools) {
        builder.addModule(ProtocolModule.getTaskProtocol(true, lang));
    }

    // 始终注入格式协议
    builder.addModule(ProtocolModule.getFormattingProtocol(lang));

    return builder.build();
}
