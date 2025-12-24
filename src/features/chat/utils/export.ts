import * as FileSystem from 'expo-file-system';
import { Platform, Share } from 'react-native';

const { StorageAccessFramework } = FileSystem;

export const exportChatToTxt = async (title: string, content: string) => {
    const fileName = `${title.replace(/[^a-z0-9]/gi, '_').toLowerCase()}.txt`;

    try {
        if (Platform.OS === 'android') {
            const permissions = await StorageAccessFramework.requestDirectoryPermissionsAsync();

            if (permissions.granted) {
                const uri = await StorageAccessFramework.createFileAsync(
                    permissions.directoryUri,
                    fileName,
                    'text/plain'
                );

                await FileSystem.writeAsStringAsync(uri, content, { encoding: FileSystem.EncodingType.UTF8 });
                return { success: true, uri };
            }
            return { success: false, error: 'Permission denied' };
        } else {
            // iOS / other
            const fileUri = FileSystem.documentDirectory + fileName;
            await FileSystem.writeAsStringAsync(fileUri, content);

            await Share.share({
                url: fileUri,
                title: `Export: ${title}`,
            });
            return { success: true, uri: fileUri };
        }
    } catch (e) {
        console.error('Export failed:', e);
        return { success: false, error: (e as Error).message };
    }
};
