import * as ExpoHaptics from 'expo-haptics';
import { useSettingsStore } from '../store/settings-store';

/**
 * Global Haptics Wrapper
 * Respects the user's global haptics setting (hapticsEnabled).
 */

const isHapticsEnabled = () => {
    return useSettingsStore.getState().hapticsEnabled;
};

export async function selectionAsync() {
    if (useSettingsStore.getState().hapticsEnabled) {
        setTimeout(async () => {
            try {
                await ExpoHaptics.selectionAsync();
            } catch (e) {
                console.warn('Haptics failed', e);
            }
        }, 10);
    }
}

export async function notificationAsync(type: ExpoHaptics.NotificationFeedbackType) {
    if (useSettingsStore.getState().hapticsEnabled) {
        setTimeout(async () => {
            try {
                await ExpoHaptics.notificationAsync(type);
            } catch (e) {
                console.warn('Haptics failed', e);
            }
        }, 10);
    }
}

export async function impactAsync(style: ExpoHaptics.ImpactFeedbackStyle) {
    if (useSettingsStore.getState().hapticsEnabled) {
        setTimeout(async () => {
            try {
                await ExpoHaptics.impactAsync(style);
            } catch (e) {
                console.warn('Haptics failed', e);
            }
        }, 10);
    }
}

// Re-export constants for convenience
export const NotificationFeedbackType = ExpoHaptics.NotificationFeedbackType;
export const ImpactFeedbackStyle = ExpoHaptics.ImpactFeedbackStyle;
