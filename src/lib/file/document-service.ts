import * as DocumentPicker from 'expo-document-picker';
import { ChatAttachment } from '../../types/chat';
import { documentProcessor } from '../rag/document-processor';

export class DocumentService {
    /**
     * Pick a document from the system file picker
     */
    async pickDocument(): Promise<ChatAttachment | null> {
        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: [
                    'text/plain',
                    'text/markdown',
                    'application/pdf',
                    'application/json',
                    'application/vnd.openxmlformats-officedocument.wordprocessingml.document', // docx
                    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', // xlsx
                    'application/vnd.ms-excel', // xls
                    'text/csv'
                ],
                copyToCacheDirectory: true,
            });

            if (result.canceled || !result.assets || result.assets.length === 0) {
                return null;
            }

            const asset = result.assets[0];

            return {
                id: `doc-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
                name: asset.name,
                size: asset.size || 0,
                mimeType: asset.mimeType || 'application/octet-stream',
                uri: asset.uri,
            };
        } catch (e) {
            console.error('[DocumentService] Error picking document:', e);
            return null;
        }
    }

    /**
     * Extract text from a document for fallback models
     */
    async extractText(file: ChatAttachment): Promise<string> {
        try {
            console.log(`[DocumentService] Extracting text for ${file.name} (${file.mimeType})`);
            const result = await documentProcessor.processFile(file.uri, file.name, file.mimeType);

            // If result content is empty or failed, return a placeholder or empty string
            if (!result.content) {
                console.warn(`[DocumentService] No content extracted for ${file.name}`);
                return '';
            }

            return result.content;
        } catch (e) {
            console.error(`[DocumentService] Error extracting text from ${file.name}:`, e);
            return `[Error extracting content from ${file.name}]`;
        }
    }
}

export const documentService = new DocumentService();
