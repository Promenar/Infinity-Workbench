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

    // ✅ 优化策略：如果正文为空，总结文本已由 ChatBubble 主体渲染。
    // 此处仅需显示“最终结论”的徽章/标题即可，不再重复渲染内容。
    // 我们保留 BlurView 外观作为章节分割线。
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
                    borderRadius: 12, // Slightly reduced radius for badge look
                }}
            >
                <View style={{ paddingHorizontal: 16, paddingVertical: 10 }}>
                    <View className="flex-row items-center">
                        <CheckCircle2 size={14} color="#22c55e" />
                        <Text className="text-[12px] font-bold text-green-700 dark:text-green-300 ml-1.5 uppercase">
                            {t.skills.timeline.finalResult}
                        </Text>
                    </View>
                </View>
            </BlurView>
        </View>
    );
};
