import { useApiStore, ModelConfig } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';
import { createLlmClient } from '../llm/factory';
import { ChatMessage } from '../llm/types';

export class ImageDescriptionService {
    /**
     * Finds the best available Vision-enabled model
     */
    private getVisionModel(): { model: ModelConfig, provider: any } | null {
        const apiStore = useApiStore.getState();
        const settings = useSettingsStore.getState();

        // 1. Check if user set a specific VLM in future settings (not yet implemented), 
        // for now, search for any enabled model with vision capability.

        // Search in enabled models
        for (const provider of apiStore.providers) {
            if (!provider.enabled) continue;

            // Check specifically enabled models for this provider
            const enabledModelIds = apiStore.enabledModels[provider.id] || [];

            for (const model of provider.models) {
                if (enabledModelIds.includes(model.uuid) && model.capabilities.vision) {
                    return { model, provider };
                }
            }
        }

        // Fallback: search any model with 'gpt-4-vision', 'gemini-pro-vision', 'claude-3' in ID if capability flag is missing
        // (But hopefully capabilities are set correctly)

        return null;
    }

    /**
     * Generates a description for an image
     * @param base64Image Base64 encoded image string (with or without prefix)
     * @returns The generated description
     */
    async describeImage(base64Image: string): Promise<string> {
        const result = this.getVisionModel();
        if (!result) {
            throw new Error('No vision-enabled model found. Please enable a model with vision capabilities (e.g. GPT-4o, Gemini Pro Vision).');
        }

        const { model, provider } = result;

        // Ensure base64 prefix
        const imageUrl = base64Image.startsWith('data:')
            ? base64Image
            : `data:image/jpeg;base64,${base64Image}`;

        const client = createLlmClient({
            ...model,
            provider: provider.type,
            apiKey: provider.apiKey,
            baseUrl: provider.baseUrl,
            vertexProject: provider.vertexProject,
            vertexLocation: provider.vertexLocation,
            vertexKeyJson: provider.vertexKeyJson
        });

        const messages: ChatMessage[] = [
            {
                role: 'user',
                content: [
                    { type: 'text', text: 'Please describe this image in detail. The description will be used for searching and retrieval. Include key objects, text, people, setting, and mood.' },
                    { type: 'image_url', image_url: { url: imageUrl } }
                ]
            }
        ];

        try {
            console.log(`[ImageService] Generating description using ${model.id}...`);
            const response = await client.chatCompletion(messages);
            return response.content;
        } catch (error) {
            console.error('[ImageService] Failed to describe image:', error);
            throw error;
        }
    }
}

export const imageDescriptionService = new ImageDescriptionService();
