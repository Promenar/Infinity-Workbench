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
    // ✅ UI Optimization Props
    isLatest?: boolean;
    pendingIntervention?: string;
}

export const TaskMonitor = ({ sessionId, containerStyle, task, isLatest = true, pendingIntervention }: RequestProps) => {
    const { isDark, colors } = useTheme();
    const dismissActiveTask = useChatStore(s => s.dismissActiveTask);
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
    const activeTask = task || session?.activeTask; // ✅ Prioritize passed task

    // ✅ Intelligent Expansion:
    // 1. Expand if intervention is needed (CRITICAL)
    // 2. Expand if it's the latest task AND in progress
    // 3. Collapse if it's history
    const [expanded, setExpanded] = useState(
        !!pendingIntervention || (isLatest && activeTask?.status === 'in-progress')
    );

    // ✅ Sync expansion with intervention
    useEffect(() => {
        if (pendingIntervention) {
            LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
            setExpanded(true);
        }
    }, [pendingIntervention]);

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
                    backgroundColor: isDark ? 'rgba(0, 0, 0, 0.05)' : 'rgba(255, 255, 255, 0.4)',
                    borderTopWidth: 0.5,
                    borderBottomWidth: 0.5,
                    borderColor: pendingIntervention
                        ? (isDark ? '#ca8a04' : '#eab308') // 🚨 Intervention: Amber Border
                        : (isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)'), // Normal
                    borderRadius: 16,
                    // 📉 History Mode: Dim opacity if not latest
                    opacity: isLatest ? 1 : 0.6,
                }}
            >
                <TouchableOpacity
                    activeOpacity={0.8}
                    onPress={toggleExpand}
                    style={{ paddingLeft: 29, paddingRight: 16, paddingVertical: 16 }}
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

                    {/* 🚨 Intervention Card (Decision Required) */}
                    {pendingIntervention && (
                        <Animated.View entering={FadeInDown.springify()} className="mt-4 mb-2 p-3 bg-amber-500/10 dark:bg-amber-500/20 rounded-xl border border-amber-500/30">
                            <View className="flex-row items-center mb-2">
                                <Loader2 size={14} color="#eab308" className="animate-spin mr-2" />
                                <Text className="text-amber-600 dark:text-amber-400 font-bold text-xs uppercase tracking-wider">
                                    Decision Required
                                </Text>
                            </View>
                            <Text className="text-zinc-800 dark:text-zinc-200 text-[13px] leading-5 font-medium">
                                {pendingIntervention}
                            </Text>
                            <View className="mt-2 flex-row items-center">
                                <Text className="text-zinc-500 dark:text-zinc-400 text-[11px]">
                                    Please reply to continue...
                                </Text>
                            </View>
                        </Animated.View>
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
