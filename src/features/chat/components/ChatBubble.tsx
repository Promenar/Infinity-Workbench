import React from 'react';
import { View } from 'react-native';
import Markdown from 'react-native-markdown-display';
import { ContextMenu } from '../../../components/ui/ContextMenu';
import { Typography } from '../../../components/ui/Typography';
import { useToast } from '../../../components/ui/Toast';
import { clsx } from 'clsx';
import * as Clipboard from 'expo-clipboard';
import { useTheme } from '../../../theme/ThemeProvider';

interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
}

interface ChatBubbleProps {
    message: Message;
}

export const ChatBubble = React.memo(function ChatBubble({ message }: ChatBubbleProps) {
    const isUser = message.role === 'user';
    const { showToast } = useToast();
    const { isDark } = useTheme(); // Hook into theme

    const handleCopy = async () => {
        await Clipboard.setStringAsync(message.content);
        showToast('Copied to clipboard', 'success');
    };

    const markdownStyles = {
        body: {
            color: isUser ? '#ffffff' : 'var(--text-primary)', // Need to verify if var works in markdown style object, likely not directly. Using fallback.
            fontSize: 16,
        },
        // Add more markdown styles as needed
    };

    // Quick fix for markdown colors since it accepts JS objects, not tailwind classes
    const textColor = isUser ? 'white' : '#0f172a'; // TODO: hook into theme for dark mode

    return (
        <View className={clsx("flex-row mb-4", isUser ? "justify-end" : "justify-start")}>
            <ContextMenu
                items={[
                    { label: 'Copy', onPress: handleCopy },
                    { label: 'Share', onPress: () => { } },
                    { label: 'Delete', onPress: () => { }, destructive: true },
                ]}
            >
                <View
                    className={clsx(
                        "max-w-[85%] rounded-2xl px-4 py-3",
                        isUser
                            ? "bg-primary-600 rounded-tr-sm"
                            : "bg-surface-primary dark:bg-slate-800 border border-border-default dark:border-slate-700 rounded-tl-sm"
                    )}
                >
                    <Markdown
                        style={{
                            body: { color: isUser ? '#ffffff' : (isDark ? '#f1f5f9' : '#0f172a'), fontSize: 16 },
                            paragraph: { marginTop: 0, marginBottom: 0 },
                        }}
                    >
                        {message.content}
                    </Markdown>
                </View>
            </ContextMenu>
        </View>
    );
}, (prev, next) => {
    return prev.message.id === next.message.id && prev.message.content === next.message.content;
});
