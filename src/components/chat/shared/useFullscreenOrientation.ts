import { useState, useCallback } from 'react';
import * as ScreenOrientation from 'expo-screen-orientation';
import * as Haptics from '../../../lib/haptics';

/**
 * useFullscreenOrientation - 管理全屏模式下的横屏切换逻辑
 */
export function useFullscreenOrientation() {
    const [isFullscreen, setIsFullscreen] = useState(false);
    const [isLandscape, setIsLandscape] = useState(false);

    const enterFullscreen = useCallback(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        setIsFullscreen(true);
    }, []);

    const toggleOrientation = useCallback(async () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        const nextLandscape = !isLandscape;
        setIsLandscape(nextLandscape);

        if (nextLandscape) {
            await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE_LEFT);
        } else {
            await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP);
        }
    }, [isLandscape]);

    const exitFullscreen = useCallback(async () => {
        await ScreenOrientation.unlockAsync();
        setIsFullscreen(false);
        setIsLandscape(false);
    }, []);

    return {
        isFullscreen,
        isLandscape,
        enterFullscreen,
        toggleOrientation,
        exitFullscreen,
    };
}
