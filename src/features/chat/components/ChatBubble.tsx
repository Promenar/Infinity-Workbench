import React from 'react';
import { View } from 'react-native';
import Markdown from 'react-native-markdown-display';
import { ContextMenu } from '../../../components/ui/ContextMenu';
import { Typography } from '../../../components/ui/Typography';
import { useToast } from '../../../components/ui/Toast';
import { clsx } from 'clsx';
import * as Clipboard from 'expo-clipboard';
import { useTheme } from '../../../theme/ThemeProvider';
import * as LucideIcons from 'lucide-react-native';

interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
}

interface ChatBubbleProps {
    message: Message;
    agent?: any; // 传入 Agent 配置以显示头像
}

export const ChatBubble = React.memo(function ChatBubble({ message, agent }: ChatBubbleProps) {
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

    const IconComponent = agent?.avatar ? ((LucideIcons as any)[agent.avatar] || LucideIcons.Bot) : LucideIcons.Bot;

    return (
        <View className={clsx("mb-6 px-4", isUser ? "items-end" : "items-start")}>
            <View className={clsx("flex-row", isUser ? "justify-end" : "justify-start gap-3")}>
                {!isUser && (
                    <View
                        style={{ backgroundColor: `${agent?.color || '#6366f1'}15`, borderColor: `${agent?.color || '#6366f1'}30` }}
                        className="w-10 h-10 rounded-xl items-center justify-center border mt-1"
                    >
                        <IconComponent size={20} color={agent?.color || '#6366f1'} strokeWidth={2} />
                    </View>
                )}

                <View style={{ maxWidth: '85%', alignSelf: isUser ? 'flex-end' : 'flex-start' }}>
                    <ContextMenu
                        items={[
                            { label: 'Copy', onPress: handleCopy },
                            { label: 'Share', onPress: () => { } },
                            { label: 'Delete', onPress: () => { }, destructive: true },
                        ]}
                    >
                        <View
                            className={clsx(
                                "rounded-2xl px-4 py-3 shadow-sm",
                                isUser
                                    ? "bg-indigo-600 rounded-tr-sm"
                                    : "bg-white dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 rounded-tl-sm"
                            )}
                            style={{ minWidth: 44 }}
                        >
                            <Markdown
                                style={{
                                    body: {
                                        color: isUser ? '#ffffff' : (isDark ? '#f1f5f9' : '#111827'),
                                        fontSize: 16,
                                        lineHeight: 24,
                                    },
                                    paragraph: { marginTop: 0, marginBottom: 0 },
                                    code_inline: { backgroundColor: isDark ? '#27272a' : '#f4f4f5', paddingHorizontal: 4, borderRadius: 4, color: isDark ? '#f472b6' : '#db2777' },
                                    code_block: { backgroundColor: isDark ? '#18181b' : '#f8fafc', padding: 12, borderRadius: 12, borderWidth: 1, borderColor: isDark ? '#27272a' : '#e2e8f0', marginVertical: 8 },
                                    fence: { backgroundColor: isDark ? '#18181b' : '#f8fafc', padding: 12, borderRadius: 12, borderWidth: 1, borderColor: isDark ? '#27272a' : '#e2e8f0', marginVertical: 8 },
                                }}
                            >
                                {message.content}
                            </Markdown>
                        </View>
                    </ContextMenu>
                </View>
            </View>
        </View>
    );
}
    , (prev, next) => {
        return prev.message.id === next.message.id && prev.message.content === next.message.content;
    });
