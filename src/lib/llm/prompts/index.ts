import { PromptBuilder } from './assembler';
import { IdentityModule } from './modules/identity';
import { CapabilityModule } from './modules/capabilities';
import { ProtocolModule } from './modules/protocols';

/**
 * Main Assembler Function
 * Replaces the old "getModelSpecificEnhancements" spaghetti code.
 */
export function assembleSystemPrompt(
    modelFamily: string,
    options: {
        hasTools?: boolean;
        hasNativeSearch?: boolean;
        customPersona?: string;
    } = {}
): string {
    const builder = new PromptBuilder();

    // 1. Identity (Kernel + Persona)
    builder.addModule(IdentityModule.getKernelIdentity());
    builder.addModule(IdentityModule.getPersona(options.customPersona));

    // 2. Capability
    if (options.hasTools) {
        builder.addModule(CapabilityModule.getToolPhilosophy(options.hasNativeSearch));
    }
    builder.addModule(CapabilityModule.getRendererCapabilities());
    builder.addModule(CapabilityModule.getKnowledgeContext());

    // 3. Protocol
    builder.addModule(ProtocolModule.getThinkingProtocol());

    // Only inject Task Protocol if tools are enabled (which implies complex tasks)
    if (options.hasTools) {
        builder.addModule(ProtocolModule.getTaskProtocol(true));
    }

    // Always inject Formatting Protocol
    builder.addModule(ProtocolModule.getFormattingProtocol());

    return builder.build();
}
