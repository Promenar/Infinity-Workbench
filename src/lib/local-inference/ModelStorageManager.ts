import { getInfoAsync, makeDirectoryAsync, readDirectoryAsync, copyAsync, deleteAsync, documentDirectory } from 'expo-file-system/legacy';
import * as DocumentPicker from 'expo-document-picker';

export interface LocalModelFile {
    name: string;
    size: number;
    uri: string;
    path: string;
}

const MODELS_DIR = `${documentDirectory}models/`;

export const ModelStorageManager = {
    /**
     * Ensure models directory exists
     */
    init: async () => {
        const info = await getInfoAsync(MODELS_DIR);
        if (!info.exists) {
            await makeDirectoryAsync(MODELS_DIR, { intermediates: true });
        }
    },

    /**
     * List all available GGUF models
     */
    listModels: async (): Promise<LocalModelFile[]> => {
        await ModelStorageManager.init();
        const files = await readDirectoryAsync(MODELS_DIR);

        const models: LocalModelFile[] = [];

        for (const file of files) {
            if (file.endsWith('.gguf')) {
                const uri = MODELS_DIR + file;
                const info = await getInfoAsync(uri);
                if (info.exists) {
                    models.push({
                        name: file,
                        size: info.size || 0,
                        uri: uri,
                        path: uri.replace('file://', ''), // Clean path for llama.rn
                    });
                }
            }
        }

        return models;
    },

    /**
     * Import a model from Document Picker
     */
    importModel: async (): Promise<LocalModelFile | null> => {
        await ModelStorageManager.init();

        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: '*/*', // GGUF mime type might vary, allow all and filter ext
                copyToCacheDirectory: true
            });

            if (result.canceled || !result.assets || result.assets.length === 0) {
                return null;
            }

            const asset = result.assets[0];
            const fileName = asset.name;

            if (!fileName.toLowerCase().endsWith('.gguf')) {
                throw new Error('Please select a .gguf file');
            }

            const destPath = MODELS_DIR + fileName;

            // Move or Copy (Copy safer if cache is temp)
            await copyAsync({
                from: asset.uri,
                to: destPath
            });

            return {
                name: fileName,
                size: asset.size || 0,
                uri: destPath,
                path: destPath.replace('file://', '')
            };

        } catch (error) {
            console.error('[ModelStorage] Import failed:', error);
            throw error;
        }
    },

    /**
     * Delete a model
     */
    deleteModel: async (fileName: string) => {
        const path = MODELS_DIR + fileName;
        await deleteAsync(path, { idempotent: true });
    }
};
