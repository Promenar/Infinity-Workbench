import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, LayoutAnimation, Platform, UIManager } from 'react-native';
import { BlurView } from 'expo-blur';
import { ChevronDown, ChevronUp, Circle, CheckCircle2, Loader2, XCircle, SkipForward } from 'lucide-react-native';
import Animated, { FadeInDown, Layout, FadeIn } from 'react-native-reanimated';
import * as Haptics from 'expo-haptics';
import { useTheme } from '../../../theme/ThemeProvider';
import { useChatStore } from '../../../store/chat-store';
import { TaskStep } from '../../../types/chat';

// Enable layout animation on Android
if (Platform.OS === 'android') {
    if (UIManager.setLayoutAnimationEnabledExperimental) {
        UIManager.setLayoutAnimationEnabledExperimental(true);
    }
}

interface RequestProps {
    sessionId: string;
    headerHeight: number;
}

export const TaskMonitor = ({ sessionId, headerHeight }: RequestProps) => {
    const { isDark, colors } = useTheme();
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
    const activeTask = session?.activeTask;

    const [expanded, setExpanded] = useState(false);

    // If no active task or task is completed for a long time (?) - actually, persistent means always show if exists
    // But maybe hide if status is 'completed' and user collapses it?
    // For now, simple logic: show if activeTask exists.
    if (!activeTask) return null;

    const toggleExpand = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
        setExpanded(!expanded);
    };

    const getStatusIcon = (status: TaskStep['status']) => {
        switch (status) {
            case 'completed': return <CheckCircle2 size={16} color="#22c55e" />; // green-500
            case 'in-progress': return <Loader2 size={16} color={colors[500]} className="animate-spin" />;
            case 'failed': return <XCircle size={16} color="#ef4444" />; // red-500
            case 'skipped': return <SkipForward size={16} color="#fbbf24" />; // amber-400
            default: return <Circle size={16} color={isDark ? '#52525b' : '#d4d4d8'} />; // zinc-600/300
        }
    };

    const currentStepIndex = activeTask.steps.findIndex(s => s.status === 'in-progress');
    const displayStepIndex = currentStepIndex !== -1 ? currentStepIndex : (activeTask.status === 'completed' ? activeTask.steps.length - 1 : 0);
    const currentStep = activeTask.steps[displayStepIndex] || activeTask.steps[0];

    // Progress Ring Logic (simplified as text for now, maybe ring later)
    const progressText = `${Math.round(activeTask.progress)}%`;

    return (
        <View
            style={{
                position: 'absolute',
                top: headerHeight,
                left: 0,
                right: 0,
                zIndex: 100,
            }}
            className="shadow-sm"
        >
            <BlurView
                intensity={isDark ? 80 : 95}
                tint={isDark ? 'dark' : 'light'}
                className="overflow-hidden border-b-[0.5px]"
                style={{
                    borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                    backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : 'rgba(255, 255, 255, 0.7)'
                }}
            >
                <TouchableOpacity
                    activeOpacity={0.8}
                    onPress={toggleExpand}
                    className="px-4 py-3"
                >
                    {/* Header Row: Title + Progress + Chevron */}
                    <View className="flex-row items-center justify-between">
                        <View className="flex-1 flex-row items-center">
                            {/* Mini Status Indicator */}
                            <View className={`w-1.5 h-1.5 rounded-full mr-2 ${activeTask.status === 'in-progress' ? 'bg-primary-500 animate-pulse' :
                                    activeTask.status === 'completed' ? 'bg-green-500' : 'bg-zinc-400'
                                }`} />

                            <Text numberOfLines={1} className="font-semibold text-[13px] text-zinc-900 dark:text-zinc-100 flex-1 mr-2">
                                {activeTask.title}
                            </Text>

                            {/* Micro Progress Pill */}
                            <View className="bg-zinc-100 dark:bg-zinc-800 px-1.5 py-0.5 rounded flex-row items-center ml-2">
                                <Text className="text-[10px] font-medium text-zinc-500 dark:text-zinc-400">
                                    {displayStepIndex + 1}/{activeTask.steps.length} • {progressText}
                                </Text>
                            </View>
                        </View>

                        <View className="ml-2">
                            {expanded ? <ChevronUp size={16} color={isDark ? '#a1a1aa' : '#71717a'} /> : <ChevronDown size={16} color={isDark ? '#a1a1aa' : '#71717a'} />}
                        </View>
                    </View>

                    {/* Expanded Content: Step List */}
                    {expanded && (
                        <View className="mt-3 space-y-3 pb-1">
                            {activeTask.steps.map((step, index) => (
                                <Animated.View
                                    key={step.id}
                                    entering={FadeInDown.delay(index * 50).springify()}
                                    className="flex-row items-start"
                                >
                                    <View className="pt-0.5 mr-3">
                                        {getStatusIcon(step.status)}
                                    </View>
                                    <View className="flex-1">
                                        <Text className={`text-[13px] leading-5 ${step.status === 'completed' ? 'text-zinc-400 line-through' :
                                                step.status === 'in-progress' ? 'text-primary-500 font-medium' :
                                                    'text-zinc-700 dark:text-zinc-300'
                                            }`}>
                                            {step.title}
                                        </Text>
                                        {step.description && step.status === 'in-progress' && (
                                            <Text className="text-[11px] text-zinc-500 mt-0.5 leading-4">
                                                {step.description}
                                            </Text>
                                        )}
                                    </View>
                                </Animated.View>
                            ))}
                        </View>
                    )}

                    {/* Collapsed Preview: Current Step (Only if not expanded) */}
                    {!expanded && activeTask.status === 'in-progress' && (
                        <Animated.View entering={FadeIn} className="mt-1 flex-row items-center ml-3.5 pl-3 border-l-2 border-primary-500/30">
                            <Text numberOfLines={1} className="text-[11px] text-zinc-500 dark:text-zinc-400 italic">
                                Current: {currentStep.title}
                            </Text>
                        </Animated.View>
                    )}

                </TouchableOpacity>
            </BlurView>
        </View>
    );
};
