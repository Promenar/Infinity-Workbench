import React, { useEffect, useState } from 'react';

import { SvgXml } from 'react-native-svg';
// TODO: Migrate to new Expo FileSystem API (SDK 54+) using File/Directory classes.
// Current implementation uses 'legacy' bridge which will be deprecated in future versions.
import * as FileSystem from 'expo-file-system/legacy';
import { CacheManager } from '../../lib/cache/cache-manager';
import { View } from 'react-native';

interface CachedSvgUriProps {
    uri: string;
    width?: number | string;
    height?: number | string;
    color?: string; // Optional color tinting if supported by SVG content (usually needs currentColor)
    style?: any;
}

/**
 * 具备本地缓存能力的 SvgUri 组件 (Refactored to SvgXml for stability)
 * 解决远程 SVG 每次加载闪烁及离线无法显示的问题，并避免 SvgUri 在 Android 上的解析崩溃
 */
export const CachedSvgUri: React.FC<CachedSvgUriProps> = ({ uri, width, height, color, style }) => {
    const [xmlContent, setXmlContent] = useState<string | null>(null);
    const [error, setError] = useState(false);

    useEffect(() => {
        let isMounted = true;

        const loadIcon = async () => {
            try {
                if (!uri) return;

                // 1. 尝试获取缓存路径
                let localPath = await CacheManager.get(uri);

                // 2. 如果没有缓存，下载并缓存
                if (!localPath) {
                    localPath = await CacheManager.downloadAndCache(uri);
                }

                if (!localPath) {
                    throw new Error('Failed to resolve local path');
                }

                // 3. 读取文件内容 (比 SvgUri 更稳健，避免 Native Parser 崩溃)
                // console.log('[CachedSvgUri] Reading local file:', localPath);
                const content = await FileSystem.readAsStringAsync(localPath, { encoding: 'utf8' });

                // 4. 简单校验 (更宽松的校验)
                const trimmed = content.trim();
                // console.log('[CachedSvgUri] Content preview:', trimmed.substring(0, 50));

                if (!trimmed || (!trimmed.toLowerCase().includes('<svg'))) {
                    console.warn('[CachedSvgUri] Invalid SVG content (missing <svg> tag):', uri);
                    // 尝试仍然渲染，也许只是 xml 声明在前面
                }

                if (isMounted) {
                    setXmlContent(content);
                }
            } catch (e) {
                console.warn('[CachedSvgUri] Failed to load icon:', uri, e);
                if (isMounted) setError(true);
            }
        };

        loadIcon();

        return () => {
            isMounted = false;
        };
    }, [uri]);

    if (error || !xmlContent) {
        // 错误或加载中：显示透明占位
        return <View style={[{ width: Number(width) || 24, height: Number(height) || 24 }, style]} />;
    }

    return (
        <SvgXml
            width={width}
            height={height}
            xml={xmlContent}
            color={color}
            style={style}
            onError={(e) => {
                console.warn('[CachedSvgUri] XML Render Error:', e);
                // Fix: Avoid setState during render
                setTimeout(() => {
                    setError(true);
                }, 0);
            }}
        />
    );
};
