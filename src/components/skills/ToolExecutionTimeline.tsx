
import React, { useState } from 'react';
import { View, TouchableOpacity, ScrollView, Image, ActivityIndicator } from 'react-native';
import Animated, { FadeInUp, FadeOutUp, Layout } from 'react-native-reanimated';
import { Typography } from '../ui/Typography';
import { ChevronDown, ChevronRight, Brain, Globe, Database, Image as ImageIcon, Terminal, Share2, X } from 'lucide-react-native';
import clsx from 'clsx';
import * as Sharing from 'expo-sharing';
import ImageView from 'react-native-image-viewing';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ToolCall, ExecutionStep } from '../../types/skills';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';

interface Props {
    steps: ExecutionStep[];
}

const StepIcon = ({ type, toolName }: { type: string, toolName?: string }) => {
    if (type === 'thinking') return <Brain size={16} color="#A0A0A0" />;
    if (type === 'error') return <Typography variant="caption" color="danger">!</Typography>;

    // Tool Icons
    if (toolName === 'search_internet') return <Globe size={16} color="#4F8EF7" />;
    if (toolName === 'query_vector_db') return <Database size={16} color="#FF9F43" />;
    if (toolName === 'generate_image') return <ImageIcon size={16} color="#2ED573" />;

    return <Terminal size={16} color="#A0A0A0" />; // Default tool icon
};

const TimelineItem = ({ step, isLast }: { step: ExecutionStep, isLast: boolean }) => {
    const [expanded, setExpanded] = useState(false);
    const [viewerVisible, setViewerVisible] = useState(false);
    const [sharing, setSharing] = useState(false);
    const { t } = useI18n();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    const getTitle = () => {
        const skillName = step.toolName ? (t.skills.names[step.toolName as keyof typeof t.skills.names] || step.toolName) : '';

        switch (step.type) {
            case 'thinking': return t.skills.timeline.thinking || 'Thinking Process';
            case 'tool_call': return t.skills.timeline.using.replace('{name}', skillName);
            case 'tool_result': return t.skills.timeline.result.replace('{name}', skillName);
            case 'error': return t.skills.timeline.error;
        }
    };

    // Auto-collapse thinking steps by default
    React.useEffect(() => {
        if (step.type === 'thinking') {
            setExpanded(false);
        }
    }, [step.type]);

    const getPreview = () => {
        if (step.type === 'tool_call') {
            return JSON.stringify(step.toolArgs).substring(0, 50) + '...';
        }
        return (step.content || '').substring(0, 60) + '...';
    };

    // Helper to extract URI from generate_image result
    const getImageUri = () => {
        if (step.toolName === 'generate_image' && step.type === 'tool_result' && step.content) {
            // Match file:// or data: URIs
            const match = step.content.match(/(file:\/\/\S+|data:image\/\S+;base64,\S+)/);
            return match ? match[1] : null;
        }
        return null;
    };

    const imageUri = getImageUri();

    const handleShare = async () => {
        if (!imageUri) return;
        try {
            setSharing(true);
            const isAvailable = await Sharing.isAvailableAsync();
            if (isAvailable) {
                await Sharing.shareAsync(imageUri, {
                    mimeType: 'image/png',
                    dialogTitle: 'Share or Save generated image',
                });
            }
        } catch (error) {
            console.error('[Timeline] Share failed:', error);
        } finally {
            setSharing(false);
        }
    };

    return (
        <Animated.View layout={Layout.springify()} className="flex-row">
            {/* ImageViewer Modal */}
            {imageUri && (
                <ImageView
                    images={[{ uri: imageUri }]}
                    imageIndex={0}
                    visible={viewerVisible}
                    onRequestClose={() => setViewerVisible(false)}
                    swipeToCloseEnabled={true}
                    doubleTapToZoomEnabled={true}
                    HeaderComponent={({ imageIndex }) => (
                        <View
                            className="flex-row justify-end px-4"
                            style={{ paddingTop: insets.top + 10 }}
                        >
                            <TouchableOpacity
                                onPress={() => setViewerVisible(false)}
                                className="w-10 h-10 rounded-full bg-black/40 items-center justify-center border border-white/10"
                            >
                                <X size={20} color="white" />
                            </TouchableOpacity>
                        </View>
                    )}
                />
            )}

            {/* Visual Line */}
            <Animated.View layout={Layout.springify()} className="items-center mr-3 w-4">
                <View className={clsx(
                    "w-6 h-6 rounded-full items-center justify-center border",
                    step.type === 'error' ? "border-red-500 bg-red-500/20" : "border-white/10 bg-white/5"
                )}>
                    <StepIcon type={step.type} toolName={step.toolName} />
                </View>
                {!isLast && <Animated.View layout={Layout.springify()} className="w-[1px] flex-1 my-1" style={{ backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)' }} />}
            </Animated.View>

            {/* Content */}
            <Animated.View layout={Layout.springify()} className="flex-1 pb-4">
                <TouchableOpacity
                    activeOpacity={0.7}
                    onPress={() => setExpanded(!expanded)}
                    className="flex-row items-center justify-between bg-white/5 rounded-lg p-2 border border-white/5"
                >
                    <View className="flex-1 mr-2">
                        <Typography variant="caption" className="font-bold" style={{ color: isDark ? 'rgba(255,255,255,0.8)' : 'rgba(0,0,0,0.8)' }}>
                            {getTitle()}
                        </Typography>
                        {!expanded && (
                            <Typography variant="caption" color="secondary" numberOfLines={1}>
                                {getPreview()}
                            </Typography>
                        )}
                    </View>
                    <View className="flex-row items-center">
                        {imageUri && !expanded && <ImageIcon size={14} color="#2ED573" className="mr-2" />}
                        {expanded ? <ChevronDown size={14} color="#666" /> : <ChevronRight size={14} color="#666" />}
                    </View>
                </TouchableOpacity>

                {expanded && (
                    <Animated.View
                        entering={FadeInUp.springify().damping(20).stiffness(150)}
                        exiting={FadeOutUp.duration(200)}
                        className="mt-2 pl-2 border-l-2"
                        style={{ borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)' }}
                    >
                        {step.type === 'tool_call' && step.toolArgs && (
                            <Typography variant="caption" className="font-mono text-xs text-blue-300">
                                {JSON.stringify(step.toolArgs, null, 2)}
                            </Typography>
                        )}

                        {/* Image Rendering */}
                        {imageUri ? (
                            <View className="mt-2 rounded-lg overflow-hidden border border-white/10 bg-white/5">
                                <TouchableOpacity
                                    activeOpacity={0.9}
                                    onPress={() => setViewerVisible(true)}
                                >
                                    <Image
                                        source={{ uri: imageUri }}
                                        className="w-full aspect-square"
                                        resizeMode="cover"
                                    />
                                </TouchableOpacity>
                                <View className="bg-black/50 p-2 flex-row items-center justify-between">
                                    <Typography variant="caption" className="text-[10px] text-white/50 flex-1 mr-4" numberOfLines={1}>
                                        {imageUri.split('/').pop()}
                                    </Typography>
                                    <View className="flex-row items-center gap-x-3">
                                        <TouchableOpacity onPress={handleShare} disabled={sharing}>
                                            {sharing ? (
                                                <ActivityIndicator size="small" color="#AAA" />
                                            ) : (
                                                <Share2 size={16} color="#AAA" />
                                            )}
                                        </TouchableOpacity>
                                    </View>
                                </View>
                            </View>
                        ) : (
                            <Typography variant="body" className="text-sm mt-1" style={{ color: isDark ? 'rgba(255,255,255,0.7)' : 'rgba(0,0,0,0.7)' }}>
                                {step.content}
                            </Typography>
                        )}
                    </Animated.View>
                )}
            </Animated.View>
        </Animated.View>
    );
};

export const ToolExecutionTimeline: React.FC<Props> = ({ steps }) => {
    if (!steps || steps.length === 0) return null;

    return (
        <View className="py-2 my-1">
            {steps.map((step, index) => (
                <TimelineItem
                    key={step.id}
                    step={step}
                    isLast={index === steps.length - 1}
                />
            ))}
        </View>
    );
};
