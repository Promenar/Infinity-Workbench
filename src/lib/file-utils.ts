import { Buffer } from 'buffer';
import * as FileSystem from 'expo-file-system/legacy';
import iconv from 'iconv-lite';

/**
 * 读取文件内容并自动处理编码（支持 UTF-8 和 GBK）
 * @param uri 文件 URI
 * @returns 解码后的字符串内容
 */
/**
 * 读取文件内容并自动处理编码（支持 UTF-8 和 GBK）
 * @param uri 文件 URI
 * @returns 解码后的字符串内容
 */
export const readFileAsBase64 = async (uri: string): Promise<string> => {
    return await FileSystem.readAsStringAsync(uri, {
        encoding: FileSystem.EncodingType.Base64
    });
};

export const readFileContent = async (uri: string): Promise<string> => {
    try {
        // 1. 读取为 Base64
        const base64 = await FileSystem.readAsStringAsync(uri, {
            encoding: FileSystem.EncodingType.Base64
        });

        // 2. 转换为 Buffer
        const buffer = Buffer.from(base64, 'base64');

        // 3. 尝试检测编码
        // 策略: 优先尝试 UTF-8。如果检测到大量的替换字符或无效序列，则尝试 GBK。
        // 由于 iconv-lite 本身不带探测功能，我们使用一个简单的启发式方法：
        // 尝试用 UTF-8 解码，检查是否存在乱码特征（）。

        // 注意：严格来说，应该使用 jschardet，但为了减小体积，先使用双重尝试法。

        const utf8Content = iconv.decode(buffer, 'utf-8');

        // 检查是否存在典型的乱码替换字符 ( / U+FFFD)
        // 如果文件本身就是 UTF-8 但包含特殊字符，这可能会误判，但在纯文本场景下概率较低。
        // 另一个特征是 GBK 编码的文件如果强行用 UTF-8 解码，通常会有大量的 replacement char。
        const replacementCount = (utf8Content.match(/\uFFFD/g) || []).length;

        // 阈值设为内容长度的 1% 或 10 个以上，或者是短文本中的任何一个
        const isLikelyUtf8 = replacementCount === 0 || replacementCount / utf8Content.length < 0.01;

        if (isLikelyUtf8) {
            return utf8Content;
        }

        // 4. 尝试 GBK / GB18030
        const gbkContent = iconv.decode(buffer, 'gbk');
        console.log('[FileUtils] Detected non-UTF8 content, decoded as GBK');
        return gbkContent;

    } catch (e) {
        console.error('[FileUtils] Failed to read file:', e);
        throw new Error('文件读取失败: ' + (e as Error).message);
    }
};

/**
 * 格式化文件大小
 */
export const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};
