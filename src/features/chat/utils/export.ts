import * as FileSystem from 'expo-file-system/legacy';
import { Platform, Share } from 'react-native';
import { Message, Session } from '../../../types/chat';

const fs = FileSystem as any;
const StorageAccessFramework = fs.StorageAccessFramework;
const EncodingType = fs.EncodingType;
const documentDirectory = fs.documentDirectory;

export const exportChatToTxt = async (title: string, content: string) => {
    const fileName = `${title.replace(/[^a-z0-9]/gi, '_').toLowerCase()}.txt`;

    try {
        if (Platform.OS === 'android' && StorageAccessFramework) {
            const permissions = await StorageAccessFramework.requestDirectoryPermissionsAsync();

            if (permissions.granted) {
                const uri = await StorageAccessFramework.createFileAsync(
                    permissions.directoryUri,
                    fileName,
                    'text/plain'
                );

                await FileSystem.writeAsStringAsync(uri, content, { encoding: EncodingType.UTF8 });
                return { success: true, uri };
            }
            return { success: false, error: 'Permission denied' };
        } else {
            // iOS / other
            const fileUri = (documentDirectory || '') + fileName;
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

export const exportAllSessionsToTxt = async (sessions: Session[]) => {
    let fullContent = `--- NEURALFLOW CHAT EXPORT ---\nGenerated: ${new Date().toLocaleString()}\n\n`;

    sessions.forEach((session, index) => {
        fullContent += `========================================\n`;
        fullContent += `SESSION ${index + 1}: ${session.title}\n`;
        fullContent += `AGENT: ${session.agentId}\n`;
        fullContent += `TIME: ${session.time || 'Unknown'}\n`;
        fullContent += `========================================\n\n`;

        session.messages.forEach((msg: Message) => {
            fullContent += `[${new Date(msg.timestamp).toLocaleTimeString()}] ${msg.role.toUpperCase()}:\n`;
            fullContent += `${msg.content}\n\n`;
        });

        fullContent += `\n\n`;
    });

    return exportChatToTxt('neuralflow_all_history', fullContent);
};
