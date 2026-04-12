import { useState, useCallback } from 'react';

/**
 * useWebViewHeight - 管理 WebView 高度上报逻辑
 */
export function useWebViewHeight(defaultHeight = 120, maxHeight = 240, minHeight = 80) {
    const [previewHeight, setPreviewHeight] = useState(defaultHeight);
    const [loading, setLoading] = useState(true);

    const handleHeightMessage = useCallback((data: { type: string; value: number }, isFullscreen: boolean) => {
        if (data.type === 'height' && !isFullscreen) {
            const newHeight = Math.min(Math.max(data.value, minHeight), maxHeight);
            setPreviewHeight(newHeight);
            setLoading(false);
        }
    }, [minHeight, maxHeight]);

    const resetLoading = useCallback(() => {
        setLoading(true);
    }, []);

    return {
        previewHeight,
        loading,
        setLoading,
        handleHeightMessage,
        resetLoading,
    };
}
