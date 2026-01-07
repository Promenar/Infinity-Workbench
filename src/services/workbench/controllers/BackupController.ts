import AsyncStorage from '@react-native-async-storage/async-storage';
import { RouterContext } from '../WorkbenchRouter';

const BACKUP_CONFIG_KEY = 'backup_config';

export const BackupController = {
    async getWebDavConfig(_: any, context: RouterContext) {
        try {
            const json = await AsyncStorage.getItem(BACKUP_CONFIG_KEY);
            return json ? JSON.parse(json) : {};
        } catch (e) {
            console.error('[BackupController] Failed to load config', e);
            // Return empty config instead of error to allow UI to show empty state
            return {};
        }
    },

    async updateWebDavConfig(payload: any, context: RouterContext) {
        if (!payload) throw new Error('Invalid config payload');
        try {
            await AsyncStorage.setItem(BACKUP_CONFIG_KEY, JSON.stringify(payload));
            return { success: true };
        } catch (e) {
            console.error('[BackupController] Failed to save config', e);
            throw new Error('Failed to save WebDAV config');
        }
    }
};
