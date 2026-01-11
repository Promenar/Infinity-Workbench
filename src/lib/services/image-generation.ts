import { useApiStore, ModelConfig, ProviderConfig } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';
import { createLlmClient, ExtendedModelConfig } from '../llm/factory';

/**
 * Service to handle image generation requests.
 * It uses the 'defaultImageModel' from SettingsStore, or searches for a capable model.
 */
export class ImageGenerationService {

    /**
     * Finds the best available Image Generation model.
     * Priority:
     * 1. SettingsStore.defaultImageModel
     * 2. Any enabled model with type='image'
     * 3. Any enabled model with id containing 'dall-e/flux/midjourney'
     */
    private getImageGenModel(): { model: ModelConfig; provider: ProviderConfig } {
        const apiStore = useApiStore.getState();
        const settings = useSettingsStore.getState();
        const defaultModelId = settings.defaultImageModel;
        console.log(`[ImageGen] Looking for model. Default UUID: ${defaultModelId}`);

        // 1. Try Default Setting
        if (defaultModelId) {
            for (const provider of apiStore.providers) {
                if (!provider.enabled) continue;
                const model = provider.models.find(m => m.uuid === defaultModelId);
                if (model) {
                    console.log(`[ImageGen] Found default model: ${model.name} (${model.id}) in provider ${provider.name}`);
                    return { model, provider };
                }
            }
            console.warn(`[ImageGen] Default model UUID ${defaultModelId} not found in any enabled provider.`);
        }

        // 2. Search for any enabled model with type='image'
        console.log('[ImageGen] Falling back to searching for any image model...');
        for (const provider of apiStore.providers) {
            if (!provider.enabled) continue;
            const enabledModelIds = apiStore.enabledModels[provider.id] || [];

            for (const model of provider.models) {
                if (enabledModelIds.includes(model.uuid)) {
                    // Explicit image generation model type
                    if (model.type === 'image') return { model, provider };
                    // Or ID matches (fallback)
                    if (model.id.includes('dall-e') || model.id.includes('flux') || model.id.includes('midjourney')) {
                        return { model, provider };
                    }
                }
            }
        }

        throw new Error('No image generation model found. Please configure a model in Settings -> Image Generation.');
    }

    /**
     * Generates an image based on the prompt.
     * @param prompt The prompt to generate
     * @param options Additional options like size, style
     * @returns URL of the generated image
     */
    async generateImage(prompt: string, options?: { size?: string; style?: string; quality?: string }): Promise<{ url: string; revisedPrompt?: string }> {
        const { model, provider } = this.getImageGenModel();

        console.log(`[ImageGen] Using provider client for ${model.id} via ${provider.name}`);

        // Use the LLM factory to create a client that already knows how to handle auth and endpoints.
        const extendedConfig: ExtendedModelConfig = {
            ...model,
            provider: provider.type,
            apiKey: provider.apiKey,
            baseUrl: provider.baseUrl,
            vertexProject: provider.vertexProject,
            vertexLocation: provider.vertexLocation,
            vertexKeyJson: provider.vertexKeyJson,
        };

        const client = createLlmClient(extendedConfig);

        if (!client.generateImage) {
            throw new Error(`Provider ${provider.type} does not support image generation via the client factory.`);
        }

        try {
            return await client.generateImage(prompt, options);
        } catch (e: any) {
            console.error('[ImageGen] Provider client error:', e);
            throw new Error(`Image Generation Failed: ${e.message}`);
        }
    }
}

export const imageGenerationService = new ImageGenerationService();
