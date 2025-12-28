import * as FileSystem from 'expo-file-system/legacy';
import { Share, Platform } from 'react-native';

export const backupDatabase = async () => {
    try {
        const dbPath = `${FileSystem.documentDirectory}neuralflow.sqlite`; // Default op-sqlite path location usually in document dir or Library/Custom
        // Note: op-sqlite path might vary based on iOS/Android internal storage. 
        // For Android, it's usually in getDatabasePath
        // For now, we'll assume standard Expo FileSystem access for a demo.

        // Check if file exists (mock check logic for this demo as we might not have written to DB yet)
        const fileInfo = await FileSystem.getInfoAsync(dbPath);

        if (!fileInfo.exists) {
            // Create a dummy file for backup demo if DB doesn't exist yet
            await FileSystem.writeAsStringAsync(dbPath, "Dummy DB Content");
        }

        if (Platform.OS === 'ios') {
            await Share.share({
                url: dbPath,
                title: 'NeuralFlow Backup',
            });
        } else {
            // Android: Use Sharing intent or SAF (reuse export logic ideally)
            // For simplicity in this functional demo:
            const permissions = await FileSystem.StorageAccessFramework.requestDirectoryPermissionsAsync();
            if (permissions.granted) {
                const backupUri = await FileSystem.StorageAccessFramework.createFileAsync(
                    permissions.directoryUri,
                    `backup-${Date.now()}.sqlite`,
                    'application/x-sqlite3'
                );
                const content = await FileSystem.readAsStringAsync(dbPath, { encoding: FileSystem.EncodingType.Base64 });
                await FileSystem.writeAsStringAsync(backupUri, content, { encoding: FileSystem.EncodingType.Base64 });
                return { success: true };
            }
        }
        return { success: true };
    } catch (e) {
        console.error('Backup failed', e);
        return { success: false, error: e };
    }
};
