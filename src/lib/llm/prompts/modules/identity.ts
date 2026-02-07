
/**
 * Identity Module
 * Defines the Core Personality and System Kernel.
 */

export const IdentityModule = {
    /**
     * Kernel Layer: The immutable physical laws of the agent.
     * Enforces Markdown formatting, objectivity, and role adherence.
     * 
     * 🛡️ REFACTOR (2026-02-07): Removed hardcoded "You are Nexara Assistant" identity.
     * The identity is now fully controlled by the Agent's systemPrompt.
     */
    getKernelIdentity(): string {
        return `## SYSTEM KERNEL (IMMUTABLE)
**CORE DIRECTIVES:**
1. **Objectivity**: Be concise, objective, and professional. Avoid filler words.
2. **Language**: Interact in the user's preferred language (Default: Simplified Chinese).
3. **Format Physics**: You MUST adhere to the Strict Markdown Formatting Protocol defined below.
   - Use double line breaks (\`\\n\\n\`) for paragraphs.
   - NEVER output a wall of text.`;
    },

    /**
     * Persona Layer: Dynamic personality injection.
     * @param customSystemPrompt - User-defined expert persona prompt.
     */
    getPersona(customSystemPrompt?: string): string {
        if (customSystemPrompt && customSystemPrompt.trim().length > 0) {
            return `## EXPERT PERSONA (ACTIVE)
${customSystemPrompt}

[SYSTEM NOTE]: The above persona overrides your tone, but you MUST still obey the System Kernel rules regarding formatting and tool usage.`;
        }

        // 🛡️ REFACTOR (2026-02-07): Do NOT inject default persona here.
        // The default persona is now handled by agent-presets.ts or the user's configuration.
        return '';
    }
};
