
import * as FileSystem from 'expo-file-system/legacy';
import { Platform } from 'react-native';

const CACHE_FOLDER = `${FileSystem.cacheDirectory || FileSystem.documentDirectory}svg_cache/`;

/**
 * 通用文件缓存管理器
 * 用于将远程资源（如 SVG 图标）持久化到本地文件系统
 */
export class CacheManager {
    private static initPromise: Promise<void> | null = null;

    /**
     * 确保缓存目录存在
     */
    private static async ensureCacheDir() {
        if (!this.initPromise) {
            this.initPromise = (async () => {
                try {
                    const info = await FileSystem.getInfoAsync(CACHE_FOLDER);
                    if (!info.exists) {
                        await FileSystem.makeDirectoryAsync(CACHE_FOLDER, { intermediates: true });
                    }
                } catch (error) {
                    console.error('[CacheManager] Failed to create cache directory:', error);
                }
            })();
        }
        return this.initPromise;
    }

    /**
     * 生成缓存文件名
     */
    private static getCacheKey(url: string): string {
        // 简单的 key 生成策略：提取文件名或使用 hash
        // 这里使用简单的文件名提取 + 简单的 hash 以避免特殊字符
        // 由于 URL 结构比较固定，主要是最后的文件名
        try {
            const filename = url.split('/').pop() || `cache_${Date.now()}`;
            // 移除 URL 参数
            const cleanName = filename.split('?')[0];
            // 简单的 hash 处理避免重复
            let hash = 0;
            for (let i = 0; i < url.length; i++) {
                hash = ((hash << 5) - hash) + url.charCodeAt(i);
                hash |= 0;
            }
            return `${Math.abs(hash)}_${cleanName}`;
        } catch {
            return `cache_${Date.now()}.svg`;
        }
    }

    /**
     * 获取缓存文件的本地 URI
     * @param url 远程 URL
     */
    static async get(url: string): Promise<string | null> {
        await this.ensureCacheDir();
        const filename = this.getCacheKey(url);
        const localUri = `${CACHE_FOLDER}${filename}`;

        try {
            const info = await FileSystem.getInfoAsync(localUri);
            if (info.exists) {
                return localUri;
            }
        } catch (e) {
            // ignore check error
        }
        return null;
    }

    /**
     * 下载并缓存文件
     * @param url 远程 URL
     */
    static async downloadAndCache(url: string): Promise<string> {
        await this.ensureCacheDir();
        const filename = this.getCacheKey(url);
        const localUri = `${CACHE_FOLDER}${filename}`;

        try {
            // 检查是否存在
            const info = await FileSystem.getInfoAsync(localUri);
            if (info.exists) {
                return localUri;
            }

            console.log(`[CacheManager] Downloading ${url} to ${localUri}`);
            const result = await FileSystem.downloadAsync(url, localUri);
            return result.uri;
        } catch (error) {
            console.warn(`[CacheManager] Download failed for ${url}:`, error);
            // 失败时返回原 URL (虽无法解决离线问题，但至少能尝试)
            // 但对于 SvgUri 本地模式，必须是 file:// 协议，所以这里抛出或返回 null 更合适
            // 考虑到 UI 组件需要 fallback，抛出错误让组件处理
            throw error;
        }
    }

    /**
     * 清除所有缓存
     */
    static async clearCache() {
        try {
            await FileSystem.deleteAsync(CACHE_FOLDER, { idempotent: true });
            this.initPromise = null;
        } catch (e) {
            console.error('[CacheManager] Failed to clear cache:', e);
        }
    }
}
