
import React, { useState } from 'react';
import { View, TouchableOpacity, Image, ActivityIndicator, LayoutChangeEvent } from 'react-native';
import { ScrollView } from 'react-native-gesture-handler'; // Correct import for nesting
import Animated, { FadeInUp, FadeOutUp, Layout, withTiming } from 'react-native-reanimated';
import { Typography } from '../ui/Typography';
import { ChevronDown, ChevronRight, Brain, Globe, Database, Image as ImageIcon, Terminal, Share2, X, AlertCircle, ListTodo } from 'lucide-react-native';
import clsx from 'clsx';
import * as Sharing from 'expo-sharing';
import ImageView from 'react-native-image-viewing';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ExecutionStep } from '../../types/skills';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { RagReferencesList } from '../../features/chat/components/RagReferences';
import Svg, { Defs, LinearGradient, Stop, Rect } from 'react-native-svg';

interface Props {
    steps: ExecutionStep[];
    isMessageGenerating?: boolean;
}

const StepIcon = ({ type, toolName }: { type: string, toolName?: string }) => {
    if (type === 'thinking') return <Brain size={16} color="#A0A0A0" />;
    if (type === 'error') return <AlertCircle size={16} color="#FF6B6B" />;
    if (type === 'plan_item') return <ListTodo size={16} color="#A0A0A0" />;

    // Tool Icons
    if (toolName === 'search_internet') return <Globe size={16} color="#4F8EF7" />;
    if (toolName === 'query_vector_db') return <Database size={16} color="#FF9F43" />;
    if (toolName === 'generate_image') return <ImageIcon size={16} color="#2ED573" />;

    return <Terminal size={16} color="#A0A0A0" />; // Default tool icon
};

// Helper component for Search Internet Results
const SearchResultsList = ({ sources, isDark }: { sources: any[], isDark: boolean }) => {
    if (!sources || sources.length === 0) return null;
    return (
        <View className="mt-2 space-y-2">
            {sources.map((source: any, idx: number) => (
                <View key={idx} className="p-3 rounded-xl border" style={{
                    backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#f8f9fa',
                    borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)'
                }}>
                    <View className="flex-row items-center mb-1">
                        <Globe size={12} color="#4F8EF7" className="mr-2" />
                        <Typography className="text-xs font-bold text-blue-500" numberOfLines={1}>{source.title || 'Source'}</Typography>
                    </View>
                    <Typography className="text-[10px] opacity-70 mb-1" numberOfLines={1}>{source.url}</Typography>
                    <Typography className="text-xs opacity-90" numberOfLines={2}>{source.snippet || source.content}</Typography>
                </View>
            ))}
        </View>
    );
};

// Memoize TimelineItem to prevent unnecessary re-renders during streaming
const TimelineItemComponent = ({ step, isLast, isMessageGenerating }: { step: ExecutionStep, isLast: boolean, isMessageGenerating?: boolean }) => {
    const [expanded, setExpanded] = useState(false);
    const [viewerVisible, setViewerVisible] = useState(false);
    const [sharing, setSharing] = useState(false);
    const { t } = useI18n();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    const getTitle = () => {
        const skillName = step.toolName ? (t.skills.names[step.toolName as keyof typeof t.skills.names] || step.toolName) : '';

        switch (step.type) {
            case 'thinking': {
                const isStillThinking = isLast && isMessageGenerating;
                return isStillThinking ? (t.skills.timeline.thinking || 'Thinking...') : (t.skills.timeline.thought || 'Thought');
            }
            case 'plan_item': return t.skills.timeline.plan || 'Plan';
            case 'tool_call': return t.skills.timeline.using.replace('{name}', skillName);
            case 'tool_result': return t.skills.timeline.result.replace('{name}', skillName);
            case 'error': return t.skills.timeline.error;
        }
    };

    // Auto-collapse thinking steps and results (unless it's an error)
    React.useEffect(() => {
        if (step.type === 'thinking' || step.type === 'tool_result') {
            setExpanded(false);
        }
    }, [step.type]);

    const getPreview = () => {
        if (step.type === 'tool_call') {
            return JSON.stringify(step.toolArgs).substring(0, 50) + '...';
        }

        if (step.type === 'plan_item') {
            return step.content || '';
        }

        // Custom preview for tool results
        if (step.type === 'tool_result') {
            if (step.toolName === 'query_vector_db' && step.data?.references) {
                return `${step.data.references.length} result(s)`;
            }
            if (step.toolName === 'search_internet' && step.data?.sources) {
                return `${step.data.sources.length} source(s)`;
            }
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

    // Determine if we have rich content to show
    const isRagResult = step.toolName === 'query_vector_db' && step.type === 'tool_result' && step.data?.references;
    const isSearchResult = step.toolName === 'search_internet' && step.type === 'tool_result' && step.data?.sources;

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
            <Animated.View layout={Layout.springify()} className="items-center mr-3 w-6">
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
                            <Typography variant="caption" className="font-mono text-xs text-blue-300 mb-2">
                                {JSON.stringify(step.toolArgs, null, 2)}
                            </Typography>
                        )}

                        {/* Rich Result Rendering */}
                        {isRagResult ? (
                            <RagReferencesList references={step.data.references} isDark={isDark} />
                        ) : isSearchResult ? (
                            <SearchResultsList sources={step.data.sources} isDark={isDark} />
                        ) : imageUri ? (
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

// Optimization: Memoize TimelineItem to only re-render when its specific props change
const TimelineItem = React.memo(TimelineItemComponent, (prev, next) => {
    // Re-render if index changes (isLast) or generating status changes
    if (prev.isLast !== next.isLast) return false;
    if (prev.isMessageGenerating !== next.isMessageGenerating) return false;

    // Check step deep equality for crucial fields
    if (prev.step.id !== next.step.id) return false;
    if (prev.step.content !== next.step.content) return false;
    if (prev.step.timestamp !== next.step.timestamp) return false;

    return true;
});

// Gradient Overlay for "Feathered" Look without expo-linear-gradient
const GradientOverlay = ({ isTop, isDark, height = 24 }: { isTop: boolean, isDark: boolean, height?: number }) => {
    // Determine background color based on theme
    // We assume standard dark/light backgrounds from ChatBubble context
    // dark: #27272a (zinc-800) or similar. using generic helps.
    // light: #ffffff or #f4f4f5
    const color = isDark ? '#27272a' : '#ffffff';

    return (
        <View
            pointerEvents="none"
            style={{
                position: 'absolute',
                [isTop ? 'top' : 'bottom']: 0,
                left: 0,
                right: 0,
                height,
                zIndex: 10
            }}
        >
            <Svg height="100%" width="100%">
                <Defs>
                    <LinearGradient id="grad" x1="0" y1={isTop ? "0" : "1"} x2="0" y2={isTop ? "1" : "0"}>
                        <Stop offset="0" stopColor={color} stopOpacity="1" />
                        <Stop offset="1" stopColor={color} stopOpacity="0" />
                    </LinearGradient>
                </Defs>
                <Rect x="0" y="0" width="100%" height="100%" fill="url(#grad)" />
            </Svg>
        </View>
    );
};

// Create Animated component from Gesture Handler's ScrollView for better nesting support
const GHScrollView = Animated.createAnimatedComponent(ScrollView);

export const ToolExecutionTimeline: React.FC<Props> = ({ steps, isMessageGenerating }) => {
    const { isDark } = useTheme();
    const [containerHeight, setContainerHeight] = useState(0);
    const [contentHeight, setContentHeight] = useState(0);

    const scrollViewRef = React.useRef<ScrollView>(null);
    // Throttle scroll timestamp
    const lastScrollTime = React.useRef(0);

    // Auto-scroll logic with throttling (max once per 500ms)
    // We check steps.length to ensure we scroll on new items
    // But we also want to avoid hammering the UI thread
    React.useEffect(() => {
        if (isMessageGenerating && steps.length > 0) {
            const now = Date.now();
            if (now - lastScrollTime.current > 500) {
                const timer = setTimeout(() => {
                    scrollViewRef.current?.scrollToEnd({ animated: true });
                    lastScrollTime.current = Date.now();
                }, 100);
                return () => clearTimeout(timer);
            }
        }
    }, [steps.length, isMessageGenerating]);

    if (!steps || steps.length === 0) return null;

    return (
        <View className="py-2 my-1 relative" onLayout={(e) => setContainerHeight(e.nativeEvent.layout.height)} style={{ maxHeight: 260 }}>
            {/* Main Scroll Content */}
            <GHScrollView
                ref={scrollViewRef as any}
                nestedScrollEnabled={false} // Fix: Prevent outer scroll when hitting boundary
                showsVerticalScrollIndicator={false}
                fadingEdgeLength={32} // Fix: Subtle Android native feathering
                contentContainerStyle={{ paddingVertical: 8, paddingRight: 8 }}
                onContentSizeChange={(_, h) => setContentHeight(h)}
            >
                {steps.map((step, index) => (
                    <TimelineItem
                        key={step.id}
                        step={step}
                        isLast={index === steps.length - 1}
                        isMessageGenerating={isMessageGenerating}
                    />
                ))}
            </GHScrollView>
        </View>
    );
};
