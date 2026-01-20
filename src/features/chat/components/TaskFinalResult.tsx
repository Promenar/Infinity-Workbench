import React from 'react';
import { View, Text, Platform, ViewStyle } from 'react-native';
import { BlurView } from 'expo-blur';
import { CheckCircle2 } from 'lucide-react-native';
import Markdown from 'react-native-markdown-display';
import { useTheme } from '../../../theme/ThemeProvider';
import { TaskState } from '../../../types/chat';
import { useI18n } from '../../../lib/i18n';

interface TaskFinalResultProps {
    task?: TaskState;
    containerStyle?: ViewStyle;
}

export const TaskFinalResult = ({ task, containerStyle }: TaskFinalResultProps) => {
    const { isDark } = useTheme();
    const { t } = useI18n();

    if (!task || !task.final_summary || task.status !== 'completed') return null;

    return (
        <View
            style={[
                {
                    marginVertical: 4,
                },
                containerStyle
            ]}
        >
            <BlurView
                intensity={isDark ? 30 : 50}
                tint={isDark ? 'dark' : 'light'}
                className="overflow-hidden"
                style={{
                    backgroundColor: isDark ? 'rgba(0, 0, 0, 0.05)' : 'rgba(255, 255, 255, 0.4)',
                    borderTopWidth: 0.5,
                    borderBottomWidth: 0.5,
                    borderColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                    borderRadius: 16,
                }}
            >
                <View style={{ paddingLeft: 25, paddingRight: 16, paddingVertical: 16 }}>
                    <View className="flex-row items-center mb-1">
                        <CheckCircle2 size={14} color="#22c55e" />
                        <Text className="text-[12px] font-bold text-green-700 dark:text-green-300 ml-1.5 uppercase">
                            {t.skills.timeline.finalResult}
                        </Text>
                    </View>
                    <View className="px-1">
                        <Markdown
                            style={{
                                body: {
                                    color: isDark ? '#d1d5db' : '#374151', // zinc-200 : zinc-700
                                    fontSize: 13,
                                    lineHeight: 20,
                                },
                                paragraph: {
                                    marginVertical: 4,
                                },
                                list_item: {
                                    marginVertical: 2,
                                },
                                bullet_list: {
                                    marginVertical: 4,
                                },
                                strong: {
                                    fontWeight: 'bold',
                                    color: isDark ? '#e4e4e7' : '#18181b', // zinc-200 : zinc-900
                                },
                                code_inline: {
                                    backgroundColor: isDark ? 'rgba(0,0,0,0.2)' : 'rgba(255,255,255,0.5)',
                                    color: isDark ? '#a7f3d0' : '#14532d', // green-200 : green-900
                                    borderRadius: 4,
                                    paddingHorizontal: 4,
                                    paddingVertical: 1,
                                    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                    fontSize: 12,
                                }
                            }}
                        >
                            {task.final_summary}
                        </Markdown>
                    </View>
                </View>
            </BlurView>
        </View>
    );
};
