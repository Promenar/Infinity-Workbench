import { Message, GeneratedImageData } from '../../types/chat';
import { generateThumbnail, copyToCache, saveBase64ToFile } from '../image-utils';

/**
 * 预处理后的消息对
 */
export interface PreprocessedMessages {
    /** 用于显示和存储的消息（含缩略图） */
    displayMessage: Message;
    /** 用于发送给 LLM API 的消息（含原图） */
    apiMessage: Message;
}

/**
 * 消息预处理器
 * 
 * 职责：
 * 1. 用户上传图片：生成缩略图，返回双份消息
 * 2. AI 生成图片：从 Base64 生成缩略图
 * 3. 构建 API 消息格式（多模态）
 */
export class MessagePreprocessor {
    /**
     * 预处理用户消息
     * 
     * @param message 原始用户消息
     * @returns 预处理后的双份消息
     */
    static async preprocessUserMessage(message: Message): Promise<PreprocessedMessages> {
        // 没有图片，直接返回
        if (!message.images || message.images.length === 0) {
            return {
                displayMessage: message,
                apiMessage: message
            };
        }

        const processedImages: GeneratedImageData[] = [];

        for (const img of message.images) {
            try {
                // 兼容旧格式（string URI）或新格式（GeneratedImageData）
                const originalUri = typeof img === 'string' ? img : img.original;

                // 复制到缓存目录（确保文件持久化）
                const cachedOriginal = await copyToCache(originalUri, 'originals');

                // 生成缩略图
                const thumbnail = await generateThumbnail(cachedOriginal, {
                    maxWidth: 512,
                    compress: 0.75
                });

                processedImages.push({
                    thumbnail,
                    original: cachedOriginal,
                    mime: 'image/jpeg' // 缩略图统一为 JPEG
                });
            } catch (error) {
                console.error('[MessagePreprocessor] Failed to process image:', error);
                // 降级：保留原图
                const uri = typeof img === 'string' ? img : img.original;
                processedImages.push({
                    thumbnail: uri,
                    original: uri,
                    mime: typeof img === 'string' ? 'image/jpeg' : img.mime
                });
            }
        }

        return {
            displayMessage: {
                ...message,
                images: processedImages
            },
            apiMessage: {
                ...message,
                images: processedImages
            }
        };
    }

    /**
     * 预处理 AI 生成图片（从 Base64）
     * 
     * @param base64 Base64 图片数据（不含前缀）
     * @param mimeType MIME 类型
     * @returns 缩略图 + 原图数据
     */
    static async processAIGeneratedImage(
        base64: string,
        mimeType: string = 'image/png'
    ): Promise<GeneratedImageData> {
        try {
            // 保存原图
            const originalUri = await saveBase64ToFile(base64, 'originals', mimeType);

            // 生成缩略图
            const thumbnailUri = await generateThumbnail(originalUri, {
                maxWidth: 512,
                compress: 0.75
            });

            return {
                thumbnail: thumbnailUri,
                original: originalUri,
                mime: mimeType
            };
        } catch (error) {
            console.error('[MessagePreprocessor] Failed to process AI image:', error);
            // 降级：返回 Base64 URI（性能较差，但能显示）
            const dataUri = `data:${mimeType};base64,${base64}`;
            return {
                thumbnail: dataUri,
                original: dataUri,
                mime: mimeType
            };
        }
    }

    /**
     * 构建 API 消息格式（提取原图 URI）
     * 用于发送给 LLM Provider
     * 
     * @param message 预处理后的消息
     * @returns API 消息格式
     */
    static buildApiMessageContent(message: Message): any {
        // 纯文本消息
        if (!message.images || message.images.length === 0) {
            return message.content;
        }

        // 多模态格式（OpenAI 标准）
        const parts: any[] = [
            { type: 'text', text: message.content }
        ];

        // 添加图片（使用原图）
        for (const img of message.images) {
            parts.push({
                type: 'image_url',
                image_url: {
                    url: img.original  // 关键：使用原图而非缩略图
                }
            });
        }

        return parts;
    }
}
