import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, LayoutAnimation, Platform, UIManager, ViewStyle } from 'react-native';
import { BlurView } from 'expo-blur';
import { ChevronDown, ChevronUp, Circle, CheckCircle2, Loader2, XCircle, SkipForward, X } from 'lucide-react-native';
import Animated, { FadeInDown, Layout, FadeIn } from 'react-native-reanimated';
import * as Haptics from 'expo-haptics';
import { useTheme } from '../../../theme/ThemeProvider';
import { useChatStore } from '../../../store/chat-store';
import { TaskStep, TaskState } from '../../../types/chat';
import Markdown from 'react-native-markdown-display';

// Enable layout animation on Android
if (Platform.OS === 'android') {
    if (UIManager.setLayoutAnimationEnabledExperimental) {
        UIManager.setLayoutAnimationEnabledExperimental(true);
    }
}

interface RequestProps {
    sessionId: string;
    containerStyle?: ViewStyle;
    task?: TaskState; // ✅ Added optional task prop
}

export const TaskMonitor = ({ sessionId, containerStyle, task }: RequestProps) => {
    const { isDark, colors } = useTheme();
    const dismissActiveTask = useChatStore(s => s.dismissActiveTask);
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
    const activeTask = task || session?.activeTask; // ✅ Prioritize passed task

    const [expanded, setExpanded] = useState(false);

    if (!activeTask) return null;

    const toggleExpand = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
        setExpanded(!expanded);
    };

    const handleDismiss = (e: any) => {
        e.stopPropagation();
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        dismissActiveTask(sessionId);
    };

    const getStatusIcon = (status: TaskStep['status']) => {
        switch (status) {
            case 'completed': return <CheckCircle2 size={15} color="#22c55e" />;
            case 'in-progress': return <Loader2 size={15} color={colors[500]} strokeWidth={3} className="animate-spin" />;
            case 'failed': return <XCircle size={15} color="#ef4444" />;
            case 'skipped': return <SkipForward size={15} color="#fbbf24" />;
            default: return <Circle size={15} color={isDark ? '#52525b' : '#d4d4d8'} />;
        }
    };

    const getStatusTextClasses = (status: TaskStep['status']) => {
        switch (status) {
            case 'completed': return 'text-zinc-500 dark:text-zinc-400 font-medium';
            case 'in-progress': return 'text-primary-500 font-medium';
            default: return 'text-zinc-700 dark:text-zinc-300';
        }
    };

    const currentStepIndex = activeTask.steps.findIndex(s => s.status === 'in-progress');

    let displayStepIndex = 0;
    if (activeTask.status === 'completed') {
        displayStepIndex = activeTask.steps.length - 1;
    } else if (currentStepIndex !== -1) {
        displayStepIndex = currentStepIndex;
    } else {
        displayStepIndex = activeTask.steps.findIndex(s => s.status !== 'completed');
        if (displayStepIndex === -1) displayStepIndex = activeTask.steps.length - 1;
    }

    const currentStep = activeTask.steps[displayStepIndex] || activeTask.steps[0];

    const progressText = activeTask.status === 'completed' ? '100%' : `${Math.round(activeTask.progress)}%`;
    const stepCountText = activeTask.status === 'completed'
        ? `${activeTask.steps.length}/${activeTask.steps.length}`
        : `${Math.min(displayStepIndex + 1, activeTask.steps.length)}/${activeTask.steps.length}`;

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
                    backgroundColor: isDark ? 'rgba(21, 23, 38, 0.4)' : 'rgba(255, 255, 255, 0.4)',
                    borderTopWidth: 0.5,
                    borderBottomWidth: 0.5,
                    borderColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                }}
            >
                <TouchableOpacity
                    activeOpacity={0.8}
                    onPress={toggleExpand}
                    className="pl-8 pr-4 py-4"
                >
                    {/* Header Row: Title + Progress + Chevron */}
                    <View className="flex-row items-center justify-between">
                        <View className="flex-1 flex-row items-center">
                            {/* Mini Status Indicator */}
                            <View className={`w-1.5 h-1.5 rounded-full mr-3 ${activeTask.status === 'in-progress' ? 'bg-primary-500 animate-pulse' :
                                activeTask.status === 'completed' ? 'bg-green-500' : 'bg-zinc-400'
                                }`} />

                            <Text numberOfLines={1} className="font-bold text-[12px] text-zinc-900 dark:text-zinc-100 flex-1 mr-1 uppercase tracking-tight">
                                {activeTask.title}
                            </Text>

                            {activeTask.status === 'completed' && (
                                <View className="mr-1">
                                    <CheckCircle2 size={13} color="#22c55e" strokeWidth={3} />
                                </View>
                            )}

                            {/* Micro Progress Pill */}
                            <View className="bg-zinc-200/50 dark:bg-white/10 px-2 py-0.5 rounded flex-row items-center ml-1">
                                <Text className="text-[9px] font-bold text-zinc-600 dark:text-zinc-400">
                                    {stepCountText} • {progressText}
                                </Text>
                            </View>
                        </View>

                        <View className="flex-row items-center space-x-3 ml-2">
                            <TouchableOpacity
                                onPress={handleDismiss}
                                hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                                className="opacity-30 active:opacity-100"
                            >
                                <X size={14} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                            <View style={{ width: 1, height: 10, backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)' }} />
                            <View>
                                {expanded ? <ChevronUp size={14} color={isDark ? '#aaa' : '#666'} /> : <ChevronDown size={14} color={isDark ? '#aaa' : '#666'} />}
                            </View>
                        </View>
                    </View>

                    {/* Always ensure Final Summary is visible when completed */}
                    {activeTask.status === 'completed' && activeTask.final_summary && (
                        <View className="mt-3 mb-1 p-3 bg-green-500/10 dark:bg-green-500/20 rounded-lg border border-green-500/20">
                            <View className="flex-row items-center mb-1">
                                <CheckCircle2 size={14} color="#22c55e" />
                                <Text className="text-[12px] font-bold text-green-700 dark:text-green-300 ml-1.5 uppercase">
                                    Final Result
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
                                            color: isDark ? '#a7f3d0' : '#14532d', // green-200 : green-900 (High contrast on green card)
                                            borderRadius: 4,
                                            paddingHorizontal: 4,
                                            paddingVertical: 1,
                                            fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                            fontSize: 12,
                                        }
                                    }}
                                >
                                    {activeTask.final_summary}
                                </Markdown>
                            </View>
                        </View>
                    )}

                    {/* Expanded Content: Step List Only */}
                    {expanded && (
                        <View className="mt-3 space-y-3 pb-1">
                            {activeTask.steps.map((step, index) => (
                                <Animated.View
                                    key={step.id || `step-${index}`}
                                    entering={FadeInDown.delay(index * 50).springify()}
                                    className="flex-row items-start mb-3"
                                >
                                    <View className="mr-2.5 pt-0.5" style={{ justifyContent: 'center' }}>
                                        {getStatusIcon(step.status)}
                                    </View>
                                    <View className="flex-1">
                                        <Text className={`text-[13px] leading-5 ${getStatusTextClasses(step.status)}`}>
                                            {step.title}
                                        </Text>
                                        {step.description && (
                                            <Text className={`text-[11px] mt-0.5 leading-4 ${step.status === 'completed' ? 'text-zinc-400 dark:text-zinc-500' : 'text-zinc-500 dark:text-zinc-400'
                                                }`}>
                                                {step.description}
                                            </Text>
                                        )}
                                    </View>
                                </Animated.View>
                            ))}
                        </View>
                    )}

                    {/* Collapsed Preview: Current Step (Only if not expanded AND not completed) */}
                    {!expanded && activeTask.status === 'in-progress' && (
                        <Animated.View entering={FadeIn} className="mt-1 flex-row items-center ml-3.5 pl-3 border-l-2 border-primary-500/30">
                            <Text numberOfLines={1} className="text-[11px] text-zinc-500 dark:text-zinc-400 italic">
                                Current: {currentStep.title}
                            </Text>
                        </Animated.View>
                    )}

                </TouchableOpacity>
            </BlurView>
        </View >
    );
};
