import React, { useState, useEffect, Component, useMemo } from 'react';
import { View, TouchableOpacity, ViewStyle, Platform, Linking, Text, Modal, TextInput, ScrollView, StyleSheet, Image, Dimensions } from 'react-native';
import { Typography } from '../../../components/ui';
import { useChatStore } from '../../../store/chat-store';
import { Message } from '../../../types/chat';
import { db } from '../../../lib/db';  // ✅ 导入db
import * as Clipboard from 'expo-clipboard';
import * as Haptics from '../../../lib/haptics';
import Markdown from 'react-native-markdown-display';
import { clsx } from 'clsx';
// import { useTheme } from '../../../theme/ThemeProvider'; // Moved to parent component
import Animated, { useAnimatedStyle, useSharedValue, withTiming, withSequence, withRepeat, SharedValue, runOnJS, FadeIn, FadeOut, FadeInUp, FadeOutUp, LinearTransition } from 'react-native-reanimated';
import * as FileSystem from 'expo-file-system/legacy';
import { SvgXml } from 'react-native-svg';
import * as Sharing from 'expo-sharing';
import { captureRef } from 'react-native-view-shot';
import { AgentAvatar } from '../../../components/chat/AgentAvatar';
import { RagReferencesChip, RagReferencesList } from './RagReferences';
import { ProcessingIndicatorChip, ProcessingIndicatorDetails } from './ProcessingIndicator';

import { findModelSpec } from '../../../lib/llm/model-utils';
import { ModelIconRenderer } from '../../../components/icons/ModelIconRenderer';
import { MathRenderer, AnimatedSVGRenderer, InteractiveSVGRenderer } from '../../../components/chat/MathRenderer';
import { extractImagesFromMarkdown } from '../utils/markdown-utils';

import { parseMarkdownContent } from '../../../lib/markdown-parser';
import { SafeUserImage } from './SafeUserImage';
import { useI18n } from '../../../lib/i18n';
import {
    Copy,
    Share2,
    Check,
    RefreshCw,
    Maximize2,
    Minimize2,
    Download,
    Terminal,
    Edit2,
    Trash2,
    Type,
    ExternalLink,
    X,
    ChevronDown,
    BrainCircuit,
    Globe,
    Sparkles,
    User,
    Bot,
    Volume2,
    AlertCircle
} from 'lucide-react-native';
import { ActivityIndicator } from 'react-native';

interface ChatBubbleProps {
    message: Message;
    agentId?: string;
    agentAvatar?: string;
    agentColor?: string;
    agentName?: string;
    onDelete?: () => void;
    onLongPress?: (message: Message) => void;
    onResend?: () => void;
    onRegenerate?: () => void;
    modelId?: string;
    sessionId: string; // ✅ 新增：会话ID用于ProcessingIndicator
    isDark?: boolean;
}

// SVG 错误边界组件
class SVGErrorBoundary extends Component<{ children: React.ReactNode; isDark: boolean; t: any }, { hasError: boolean; error?: Error }> {
    constructor(props: any) {
        super(props);
        this.state = { hasError: false };
    }

    static getDerivedStateFromError(error: Error) {
        return { hasError: true, error };
    }

    componentDidCatch(error: Error, errorInfo: any) {
        console.log('SVG Rendering Error:', error, errorInfo);
    }

    render() {
        if (this.state.hasError) {
            return (
                <View style={{ marginVertical: 12, padding: 16, backgroundColor: this.props.isDark ? '#27272a' : '#fef2f2', borderRadius: 8, borderWidth: 1, borderColor: this.props.isDark ? '#3f3f46' : '#fecaca' }}>
                    <Typography style={{ color: '#ef4444', fontSize: 14, fontWeight: '600', marginBottom: 4 }}>
                        {this.props.t.agent.svgErrorTitle}
                    </Typography>
                    <Typography style={{ color: this.props.isDark ? '#a1a1aa' : '#6b7280', fontSize: 12 }}>
                        {this.props.t.agent.svgErrorDesc}
                    </Typography>
                </View>
            );
        }

        return this.props.children;
    }
}

// 生成图片组件 - 独立组件避免在 Markdown rules 中使用 hooks
const GeneratedImage: React.FC<{ src: string; alt?: string; isDark: boolean; t: any }> = React.memo(({ src, alt, isDark, t }) => {
    const [isLoading, setIsLoading] = useState(true);
    const [hasError, setHasError] = useState(false);
    const [dimensions, setDimensions] = useState<{ width: number; height: number } | null>(null);

    const handleDownload = async () => {
        try {
            if (await Sharing.isAvailableAsync()) {
                await Sharing.shareAsync(src);
                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
            } else {
                alert('Sharing is not available on this platform');
            }
        } catch (e) {
            console.error('Save failed', e);
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        }
    };

    return (
        <View style={{ marginVertical: 12 }}>
            <View style={{
                width: '100%',
                minHeight: 200,
                backgroundColor: isDark ? '#27272a' : '#f4f4f5',
                borderRadius: 12,
                overflow: 'hidden',
                position: 'relative',
                justifyContent: 'center',
                alignItems: 'center',
                borderWidth: 1,
                borderColor: isDark ? '#3f3f46' : '#e4e4e7'
            }}>
                {isLoading && (
                    <View style={{ position: 'absolute', zIndex: 10 }}>
                        <ActivityIndicator size="large" color={isDark ? '#a1a1aa' : '#6b7280'} />
                    </View>
                )}

                {hasError ? (
                    <View style={{ alignItems: 'center', padding: 20 }}>
                        <AlertCircle size={32} color="#ef4444" />
                        <Typography variant="caption" style={{ color: '#ef4444', marginTop: 8, textAlign: 'center' }}>
                            {t.agent.imageLoadError}
                        </Typography>
                        <Typography variant="caption" style={{ color: isDark ? '#71717a' : '#a1a1aa', marginTop: 4, fontSize: 11 }}>
                            {src.startsWith('data:') ? t.agent.imageTooLarge : t.agent.imagePathError}
                        </Typography>
                    </View>
                ) : (
                    <Image
                        source={{ uri: src }}
                        style={{
                            width: '100%',
                            height: dimensions ? (dimensions.height / dimensions.width) * Dimensions.get('window').width * 0.9 : 300,
                            maxHeight: 600
                        }}
                        resizeMode="contain"
                        accessibilityLabel={alt}
                        onLoad={(e) => {
                            const { width, height } = e.nativeEvent.source;
                            setDimensions({ width, height });
                            setIsLoading(false);
                        }}
                        onError={(e) => {
                            // Use warn to avoid RedBox on dev
                            console.warn('Image load error:', e.nativeEvent.error);
                            setHasError(true);
                            setIsLoading(false);
                        }}
                    />
                )}

                {!hasError && !isLoading && (
                    <TouchableOpacity
                        onPress={handleDownload}
                        style={{
                            position: 'absolute',
                            bottom: 12,
                            right: 12,
                            backgroundColor: 'rgba(0,0,0,0.7)',
                            padding: 10,
                            borderRadius: 20,
                            borderWidth: 1,
                            borderColor: 'rgba(255,255,255,0.3)'
                        }}
                    >
                        <Download size={18} color="#fff" />
                    </TouchableOpacity>
                )}
            </View>
        </View>
    );
});

const LoadingDots = ({ isDark, color }: { isDark: boolean; color?: string }) => {
    const opacity1 = useSharedValue(0.3);
    const opacity2 = useSharedValue(0.3);
    const opacity3 = useSharedValue(0.3);

    useEffect(() => {
        const loop = (sv: SharedValue<number>, delay: number) => {
            sv.value = withRepeat(
                withSequence(
                    withTiming(0.3, { duration: delay }),
                    withTiming(1, { duration: 500 }),
                    withTiming(0.3, { duration: 500 })
                ),
                -1,
                true
            );
        };
        loop(opacity1, 0);
        loop(opacity2, 200);
        loop(opacity3, 400);
    }, []);

    const dotStyle = {
        width: 6,
        height: 6,
        borderRadius: 3,
        backgroundColor: color || (isDark ? '#e4e4e7' : '#27272a'),
        marginHorizontal: 2
    };

    return (
        <View className="flex-row items-center justify-center p-2 mb-2" style={{ height: 24 }}>
            <Animated.View style={[dotStyle, useAnimatedStyle(() => ({ opacity: opacity1.value }))]} />
            <Animated.View style={[dotStyle, useAnimatedStyle(() => ({ opacity: opacity2.value }))]} />
            <Animated.View style={[dotStyle, useAnimatedStyle(() => ({ opacity: opacity3.value }))]} />
        </View>
    );
};

const SearchSourcesBlock: React.FC<{
    citations: { title: string; url: string; source?: string }[];
    isDark: boolean;
    expanded: boolean;
    onToggle: () => void;
    t: any;
}> = ({ citations, isDark, expanded, onToggle, t }) => {
    return (
        <TouchableOpacity
            activeOpacity={0.7}
            onPress={onToggle}
            style={{
                flexDirection: 'row',
                alignItems: 'center',
                backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                paddingHorizontal: 10,
                paddingVertical: 5,
                borderRadius: 20,
                borderWidth: 1,
                borderColor: expanded
                    ? (isDark ? 'rgba(99, 102, 241, 0.4)' : '#6366f1')
                    : (isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0,0,0,0.05)'),
                gap: 6
            }}
        >
            <Globe size={12} color={isDark ? (expanded ? '#818cf8' : '#a1a1aa') : (expanded ? '#6366f1' : '#64748b')} />
            <Typography style={{
                fontSize: 11,
                fontWeight: '600',
                color: isDark ? (expanded ? '#818cf8' : '#a1a1aa') : (expanded ? '#4f46e5' : '#4b5563')
            }}>
                {t.agent.citations.replace('{count}', citations.length.toString())}
            </Typography>
            <Animated.View style={{ transform: [{ rotate: expanded ? '180deg' : '0deg' }] }}>
                <ChevronDown size={11} color={isDark ? '#71717a' : '#94a3b8'} />
            </Animated.View>
        </TouchableOpacity>
    );
};

const ReasoningBlock: React.FC<{
    content: string;
    isDark: boolean;
    loading?: boolean;
    expanded: boolean;
    onToggle: () => void;
    t: any;
}> = ({ content, isDark, loading, expanded, onToggle, t }) => {
    return (
        <View>
            <TouchableOpacity
                activeOpacity={0.7}
                onPress={onToggle}
                style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                    paddingHorizontal: 10,
                    paddingVertical: 5,
                    borderRadius: 20,
                    borderWidth: 1,
                    borderColor: loading || expanded
                        ? (isDark ? 'rgba(139, 92, 246, 0.4)' : '#8b5cf6')
                        : (isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0,0,0,0.05)'),
                    gap: 6
                }}
            >
                <Sparkles size={12} color={loading ? '#8b5cf6' : (isDark ? (expanded ? '#a78bfa' : '#a1a1aa') : (expanded ? '#8b5cf6' : '#64748b'))} />
                <Typography style={{
                    fontSize: 11,
                    fontWeight: '600',
                    color: loading ? '#8b5cf6' : (isDark ? (expanded ? '#a78bfa' : '#a1a1aa') : (expanded ? '#7c3aed' : '#4b5563'))
                }}>
                    {loading ? t.agent.reasoning : (expanded ? t.agent.reasoned : t.agent.viewReasoning)}
                </Typography>
                <Animated.View style={{ transform: [{ rotate: expanded ? '180deg' : '0deg' }] }}>
                    <ChevronDown size={11} color={isDark ? '#71717a' : '#94a3b8'} />
                </Animated.View>
            </TouchableOpacity>
        </View>
    );
};

const SelectTextModal: React.FC<{
    isVisible: boolean;
    content: string;
    onClose: () => void;
    isDark: boolean;
    t: any;
}> = ({ isVisible, content, onClose, isDark, t }) => {
    const bgOpacity = useSharedValue(0);
    const contentTranslateY = useSharedValue(600);
    const [modalVisible, setModalVisible] = useState(isVisible);

    // 监听外部 isVisible 变化
    useEffect(() => {
        if (isVisible) {
            setModalVisible(true);
            bgOpacity.value = withTiming(1, { duration: 300 });
            contentTranslateY.value = withTiming(0, { duration: 400 });
        } else {
            // 当从外部关闭时（如点击背景、返回键等）
            handleDismiss();
        }
    }, [isVisible]);

    const handleDismiss = () => {
        bgOpacity.value = withTiming(0, { duration: 300 });
        contentTranslateY.value = withTiming(600, { duration: 400 }, (finished) => {
            if (finished) {
                runOnJS(setModalVisible)(false);
            }
        });
    };

    const handleCopy = async () => {
        await Clipboard.setStringAsync(content);
        setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        }, 10);
        onClose(); // 这会触发 useEffect 中的 handleDismiss
    };

    const bgStyle = useAnimatedStyle(() => ({
        opacity: bgOpacity.value,
        backgroundColor: 'rgba(0,0,0,0.5)',
        ...StyleSheet.absoluteFillObject,
    }));

    const contentStyle = useAnimatedStyle(() => ({
        transform: [{ translateY: contentTranslateY.value }],
    }));

    // 只有在 Modal 正式被 setModalVisible(false) 且动画结束时才不渲染
    if (!modalVisible && !isVisible) return null;

    return (
        <Modal
            visible={modalVisible}
            animationType="none"
            transparent={true}
            onRequestClose={onClose}
            statusBarTranslucent={true}
        >
            <View style={{ flex: 1, justifyContent: 'flex-end' }}>
                <Animated.View style={bgStyle}>
                    <TouchableOpacity
                        activeOpacity={1}
                        onPress={onClose}
                        style={{ flex: 1 }}
                    />
                </Animated.View>

                <Animated.View style={[{
                    width: '100%',
                    height: '80%',
                    backgroundColor: isDark ? '#18181b' : '#ffffff',
                    borderTopLeftRadius: 24,
                    borderTopRightRadius: 24,
                    padding: 24,
                    paddingBottom: Platform.OS === 'ios' ? 40 : 24,
                }, contentStyle]}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
                        <TouchableOpacity onPress={onClose} className="p-2">
                            <X size={24} color={isDark ? '#e4e4e7' : '#27272a'} />
                        </TouchableOpacity>
                        <Typography className="text-base font-bold">{t.agent.viewAndSelectText}</Typography>
                        <TouchableOpacity onPress={handleCopy} className="p-2 bg-indigo-500 rounded-full">
                            <Copy size={20} color="#fff" />
                        </TouchableOpacity>
                    </View>

                    <Typography variant="caption" className="mb-4 text-gray-500">
                        {t.agent.textSelectionHint}
                    </Typography>

                    <ScrollView
                        className="flex-1"
                        showsVerticalScrollIndicator={false}
                    >
                        <Typography
                            selectable={true}
                            style={{
                                color: isDark ? '#fafafa' : '#18181b',
                                fontSize: 16,
                                lineHeight: 26,
                            }}
                        >
                            {content}
                        </Typography>
                    </ScrollView>
                </Animated.View>
            </View>
        </Modal>
    );
};

const ActionBar: React.FC<{
    content: string;
    onDelete?: () => void;
    onShare: () => void;
    onSelect: () => void;
    onResend?: () => void;
    onRegenerate?: () => void;
    isDark: boolean;
    isArchived?: boolean;  // ✅ 新增：归档状态
}> = ({ content, onDelete, onShare, onSelect, onResend, onRegenerate, isDark, isArchived }) => {
    const handleCopy = async () => {
        await Clipboard.setStringAsync(content);
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        }, 10);
    };

    const iconColor = isDark ? '#a1a1aa' : '#71717a';
    const btnStyle = "p-2 mx-1";

    return (
        <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 8 }}>
            <TouchableOpacity onPress={handleCopy} className={btnStyle}>
                <Copy size={16} color={iconColor} />
            </TouchableOpacity>

            <TouchableOpacity onPress={onSelect} className={btnStyle}>
                <Type size={16} color={iconColor} />
            </TouchableOpacity>

            {onResend && (
                <TouchableOpacity onPress={onResend} className={btnStyle}>
                    <RefreshCw size={16} color={iconColor} />
                </TouchableOpacity>
            )}

            {onRegenerate && (
                <TouchableOpacity onPress={onRegenerate} className={btnStyle}>
                    <RefreshCw size={16} color={iconColor} />
                </TouchableOpacity>
            )}

            <TouchableOpacity onPress={onShare} className={btnStyle}>
                <Share2 size={16} color={iconColor} />
            </TouchableOpacity>

            {onDelete && (
                <TouchableOpacity onPress={onDelete} className={btnStyle}>
                    <Trash2 size={16} color="#ef4444" />
                </TouchableOpacity>
            )}

            {/* ✅ 归档状态绿勾 */}
            {isArchived && (
                <View className={btnStyle}>
                    <Check size={16} color="#10b981" />
                </View>
            )}
        </View>
    );
};

const ImageViewerModal = ({ visible, uri, onClose }: { visible: boolean; uri: string; onClose: () => void }) => {
    return (
        <Modal visible={visible} transparent={true} animationType="fade" onRequestClose={onClose}>
            <View style={{ flex: 1, backgroundColor: 'black', justifyContent: 'center', alignItems: 'center' }}>
                <TouchableOpacity
                    style={{ position: 'absolute', top: 50, right: 20, zIndex: 10, padding: 10 }}
                    onPress={onClose}
                >
                    <X size={28} color="white" />
                </TouchableOpacity>
                <Image
                    source={{ uri }}
                    style={{ width: Dimensions.get('window').width, height: Dimensions.get('window').height }}
                    resizeMode="contain"
                />
            </View>
        </Modal>
    );
};

const ChatBubbleComponent: React.FC<ChatBubbleProps & { isGenerating?: boolean }> = ({
    message,
    agentId,
    agentAvatar,
    agentColor = '#6366f1',
    agentName,
    onDelete,
    onLongPress,
    onResend,
    onRegenerate,
    isGenerating,
    modelId,
    sessionId, // ✅ 新增：会话ID
    // @ts-ignore
    isDark = false // Default fallback
}) => {
    const { t } = useI18n();
    const isUser = message.role === 'user';
    // const { isDark } = useTheme(); // REMOVED to avoid context crash during unmount

    // Determine avatar source
    const modelSpec = modelId ? findModelSpec(modelId) : (message.modelId ? findModelSpec(message.modelId) : null);

    // All hooks must be at top level - moved from isUser conditional block
    const [isModalVisible, setModalVisible] = useState(false);

    // Determine if message is "fresh" (less than 1s old) to prevent animation replay on scroll
    const isRecent = React.useMemo(() => {
        return (Date.now() - message.timestamp) < 1000;
    }, [message.timestamp]);
    const [isSourcesExpanded, setSourcesExpanded] = useState(false);
    const [viewImageUri, setViewImageUri] = useState<string | null>(null);
    const [bubbleWidth, setBubbleWidth] = useState(0);
    const bubbleRef = React.useRef<View>(null);

    // Moved from below to fix Hooks order error
    const [isReasoningExpanded, setReasoningExpanded] = useState(!!isGenerating);
    const [isRagExpanded, setRagExpanded] = useState(false);
    const [isProcessingExpanded, setProcessingExpanded] = useState(false); // ✅ 新增：ProcessingIndicator展开状态
    const [isArchived, setIsArchived] = useState(message.isArchived || false); // ✅ 归档状态

    // ✅ Query/Sync archive status
    useEffect(() => {
        // If store already knows it's archived, trust it
        if (message.isArchived) {
            setIsArchived(true);
            return;
        }

        const checkArchiveStatus = async () => {
            if (message.role === 'system') return;
            try {
                const result = await db.execute(
                    'SELECT 1 FROM vectors WHERE (start_message_id = ? OR end_message_id = ?) AND session_id = ? LIMIT 1',
                    [message.id, message.id, sessionId]
                );
                const hasRecord = result.rows && (
                    (result.rows as any)._array?.length > 0 ||
                    (result.rows as any).length > 0 ||
                    ((result.rows as any).item && (result.rows as any).item(0))
                );
                if (hasRecord) setIsArchived(true);
            } catch (e) {
                console.error('[ChatBubble] Failed to check archive status:', e);
            }
        };
        checkArchiveStatus();
    }, [message.id, sessionId, message.isArchived]);

    // Auto-collapse reasoning when done
    useEffect(() => {
        if (!isGenerating && message.reasoning) {
            const timer = setTimeout(() => setReasoningExpanded(false), 800);
            return () => clearTimeout(timer);
        } else if (isGenerating) {
            setReasoningExpanded(true);
        }
    }, [isGenerating]);

    const handleShare = async () => {
        if (!bubbleRef.current) return;
        try {
            const uri = await captureRef(bubbleRef.current, {
                format: 'png',
                quality: 0.9,
            });
            await Sharing.shareAsync(uri);
        } catch (error) {
            console.error('Snapshot failed', error);
        }
    };

    // 检查是否正在“思考结束后的等待输出”阶段
    // 如果是助理消息，没有内容，且 (没有思考过程 或 思考过程已结束)
    const isWaitingForContent = !isUser && isGenerating && !message.content;

    // 移除 handleLongPress 以释放原生文本选择手势

    // 只处理 LaTeX 块级公式的预处理
    // 将 $$...$$ 转换为 ```latex ... ``` 以便复用 fence 渲染逻辑
    const processedContent = useMemo(() => {
        let content = message.content || '';
        if (!content) return '';

        // 替换块级公式 $$...$$
        // 1. 临时保护代码块中的 $$（如果已有）- 简化处理，假设用户不会在代码块里写 $$ 作为文本
        // 2. 查找孤立的 $$
        const blockMathRegex = /\$\$([\s\S]+?)\$\$/g;
        return content.replace(blockMathRegex, (match, formula) => {
            return `\n\`\`\`latex\n${formula.trim()}\n\`\`\`\n`;
        });
    }, [message.content]);


    // Determine if this bubble is currently "loading" (last message and no content yet?)
    // Actually loading state is passed from parent but specific to session.
    // Ideally we assume if content is empty and has no reasoning, it's starting to load.
    // However, store updates message as soon as content arrives.
    // We can infer "loading" state for reasoning block if reasoning is present but message isn't "done" (which we don't strictly track in Message object yet, but we can assume if reasoning is happening it's generating).
    // Let's rely on props. For now, pass explicit loading prop or infer. 
    // Since ChatBubble doesn't receive `loading` prop for specific message, we use heuristics: 
    // If it's the last message and has reasoning but no content, or content is updating... 
    // Actually the requirement: "Reasoning block default open, collapse on finish". 
    // The component re-renders. We need to know when it transitions from generating to done.
    // Pass `isGenerating` is hard here without modifying parent. 
    // WORKAROUND: Expand if reasoning is short or ends with "...", collapse if long. 
    // BETTER: The prompt says "Reasoning block default open, auto collapse".
    // I will modify `ChatBubble` logic. 

    // Wait, I updated `ReasoningBlock` above to accept `loading`. But `ChatBubble` doesn't know if *this specific message* is loading.
    // The `useChat` hook knows if session is loading. 
    // But `ChatBubble` is a dumb component. 
    // For now, I will assume `loading` is true if `content` is empty OR if `reasoning` is growing (hard to track).
    // Let's implement static behavior for now: Default to collapsed unless it's the very last message?
    // User requirement: "Default open, auto collapse".
    // I can assume if it's the *last* message of the list, it *might* be loading. 
    // But I don't know index here.

    // Custom Markdown Rules to fix separate key warning in React 19 + FitImage + SVG Support + LaTeX Support
    const markdownRules = useMemo(() => ({
        fence: (node: any, children: any, parent: any, styles: any) => {
            const content = node.content?.trim() || '';
            const language = node.sourceInfo?.toLowerCase() || '';

            // 检测 LaTeX块级公式 (```latex 或 ```math)
            if (language === 'latex' || language === 'math') {
                return (
                    <View key={node.key} collapsable={false} style={{ marginVertical: 12, width: '100%' }}>
                        <MathRenderer content={content} isBlock={true} />
                    </View>
                );
            }

            // 检测 SVG：优先检查语言标签或尝试内容匹配
            if (language === 'svg' || content.startsWith('<svg') || (content.includes('<svg') && content.includes('</svg>'))) {
                // 1. 语法预检：捕获底层库必然崩溃的模式 (dM, fill#, fillred 等)
                const hasObviousSyntaxErrors =
                    /<path[^>]*\s+d[A-Z0-9]/.test(content) ||
                    /<rect[^>]*\s+x[A-Z0-9]/.test(content) ||
                    content.includes('strokeM') || content.includes('stroke#') ||
                    (content.includes('<path') && !content.includes('d='));

                if (hasObviousSyntaxErrors) {
                    return (
                        <View key={node.key} collapsable={false} style={{ marginVertical: 12, padding: 16, backgroundColor: isDark ? '#27272a' : '#fff1f2', borderRadius: 12, borderWidth: 1, borderColor: isDark ? '#3f3f46' : '#fecaca' }}>
                            <Typography selectable={true} style={{ color: '#e11d48', fontSize: 13, fontWeight: '700' }}>{t.agent.svgErrorTitle}</Typography>
                            <Typography selectable={true} variant="caption" style={{ color: isDark ? '#a1a1aa' : '#6b7280', marginTop: 4 }}>
                                {t.agent.svgBlockedDesc}
                            </Typography>
                        </View>
                    );
                }

                // 检测是否包含动画标签（CSS 动画、SMIL 动画）
                // 恢复动画检测：交由 InteractiveSVGRenderer 处理（默认静态，点击播放）
                const hasAnimation =
                    content.includes('<animate') ||
                    content.includes('<animateTransform') ||
                    content.includes('<animateMotion') ||
                    content.includes('@keyframes') ||
                    content.includes('animation:') ||
                    content.includes('<style>');

                // 如果包含动画，使用 InteractiveSVGRenderer 提供播放开关
                if (hasAnimation) {
                    return (
                        <View key={node.key + '-animated'} collapsable={false} style={{ marginVertical: 12, width: '100%' }}>
                            <InteractiveSVGRenderer svgContent={content} height={250} />
                        </View>
                    );
                }



                // 静态 SVG 继续使用 SvgXml（性能更好）
                return (
                    // @ts-ignore
                    <SVGErrorBoundary key={node.key + content.length} isDark={isDark} t={t}>
                        <View style={{ marginVertical: 12, alignItems: 'center', width: '100%' }}>
                            <View style={{ backgroundColor: isDark ? '#18181b' : '#f9fafb', padding: 12, borderRadius: 12, width: '100%', overflow: 'hidden' }}>
                                <SvgXml
                                    xml={content}
                                    width="100%"
                                    height={200}
                                    onError={() => { }} // 内部捕获
                                />
                            </View>
                        </View>
                    </SVGErrorBoundary>
                );
            }

            const handleCopyCode = async () => {
                await Clipboard.setStringAsync(content);
                setTimeout(() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                }, 10);
            };

            // 普通代码块：添加复制按钮
            return (
                <View key={node.key} style={[styles.fence, { padding: 0, overflow: 'hidden' }]}>
                    <View style={{
                        flexDirection: 'row',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        paddingHorizontal: 12,
                        paddingVertical: 8,
                        backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                        borderBottomWidth: StyleSheet.hairlineWidth,
                        borderBottomColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)',
                    }}>
                        <Typography variant="caption" style={{ color: isDark ? '#a1a1aa' : '#71717a', fontWeight: '600' }}>
                            {language.toUpperCase() || 'CODE'}
                        </Typography>
                        <TouchableOpacity onPress={handleCopyCode} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
                            <Copy size={14} color={isDark ? '#a1a1aa' : '#71717a'} />
                        </TouchableOpacity>
                    </View>
                    <View style={{ padding: 12 }}>
                        <Text
                            selectable={true}
                            style={{ fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', fontSize: 13, color: isDark ? '#e4e4e7' : '#27272a' }}
                        >
                            {content}
                        </Text>
                    </View>
                </View>
            );
        },
        image: (node: any, children: any, parent: any, styles: any) => {
            const { src, alt } = node.attributes;
            return <GeneratedImage key={node.key} src={src} alt={alt} isDark={isDark} t={t} />;
        },
    }), [isDark]);




    /**
     * User Message: "Pill" Bubble
     */
    /**
     * User Message: Minimalist Flat Style (No Bubble)
     */
    if (isUser) {
        return (
            <Animated.View
                key={message.id} // Added key to prevent Reanimated glitch during list recycling
                entering={isRecent ? FadeIn.duration(300) : undefined}
                exiting={FadeOut.duration(300)}
                layout={LinearTransition.duration(200)}
                style={{ flexDirection: 'row', justifyContent: 'flex-end', marginBottom: 32, width: '100%', paddingHorizontal: 20 }}
            >
                <View style={{ maxWidth: '80%' }}>
                    <View
                        ref={bubbleRef}
                        collapsable={false}
                        style={{
                            paddingVertical: 4,
                            alignItems: 'flex-end',
                            width: '100%',
                        }}
                    >
                        <Markdown
                            rules={markdownRules}
                            style={{
                                body: {
                                    color: isDark ? '#fafafa' : '#18181b',
                                    fontSize: 16,
                                    lineHeight: 24,
                                    fontWeight: '600',
                                    letterSpacing: 0.2,
                                    textAlign: 'left',
                                },
                                paragraph: { marginVertical: 0, paddingVertical: 0, textAlign: 'left' },
                                blockquote: {
                                    backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                                    borderLeftWidth: 4,
                                    borderLeftColor: '#6366f1',
                                    paddingHorizontal: 16,
                                    paddingVertical: 8,
                                    borderRadius: 12,
                                    marginVertical: 10,
                                },
                            }}
                            {...({ selectable: true } as any)}
                        >
                            {processedContent || ''}
                        </Markdown>
                        {message.images && message.images.length > 0 && (
                            <View style={{ flexDirection: 'row', flexWrap: 'wrap', marginTop: message.content ? 8 : 0, gap: 4 }}>
                                {message.images.map((img, index) => (
                                    <SafeUserImage
                                        key={index}
                                        uri={img.thumbnail}
                                        onPress={() => setViewImageUri(img.thumbnail)}
                                        isDark={isDark}
                                    />
                                ))}
                            </View>
                        )}
                    </View>

                    <View style={{
                        marginTop: 8,
                        borderTopWidth: StyleSheet.hairlineWidth,
                        borderTopColor: isDark ? 'rgba(39, 39, 42, 0.5)' : '#f3f4f6',
                        paddingTop: 4,
                        alignItems: 'flex-end',
                        width: '100%'
                    }}>
                        <ActionBar
                            content={message.content || ''}
                            onDelete={onDelete}
                            onShare={handleShare}
                            onSelect={() => setModalVisible(true)}
                            onResend={onResend}
                            isDark={isDark}
                            isArchived={isArchived}  // ✅ 传递归档状态
                        />
                    </View>

                    <SelectTextModal
                        isVisible={isModalVisible}
                        content={message.content || ''}
                        onClose={() => setModalVisible(false)}
                        isDark={isDark}
                        t={t}
                    />

                    {viewImageUri && (
                        <ImageViewerModal
                            visible={!!viewImageUri}
                            uri={viewImageUri}
                            onClose={() => setViewImageUri(null)}
                        />
                    )}
                </View>
            </Animated.View>
        );
    }

    /**
     * AI Message: Head-Row + Full-Width Body Layout
     */


    return (
        <Animated.View
            key={message.id} // Added key to prevent Reanimated glitch during list recycling
            entering={isRecent ? FadeIn.duration(300) : undefined}
            exiting={FadeOut.duration(300)}
            layout={LinearTransition.duration(200)}
            style={{ marginBottom: 40, width: '100%', paddingHorizontal: 20 }}
            ref={bubbleRef}
            collapsable={false}
        >
            {/* Header Row: Avatar & Status Chips */}
            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
                <View style={{
                    backgroundColor: isDark ? '#18181b' : '#ffffff',
                    borderRadius: 9999,
                    padding: 2,
                    borderWidth: 1,
                    borderColor: isDark ? '#27272a' : '#f3f4f6',
                    shadowColor: '#000',
                    shadowOffset: { width: 0, height: 1 },
                    shadowOpacity: 0.05,
                    shadowRadius: 2,
                    elevation: 1
                }}>
                    <AgentAvatar
                        id={agentId || 'ai'}
                        name={agentName || 'AI'}
                        avatar={agentAvatar || 'Sparkles'}
                        color={agentColor}
                        size={28}
                    />
                </View>

                <View className="flex-row items-center ml-3" style={{ gap: 8 }}>
                    {message.reasoning && (
                        <ReasoningBlock
                            content={message.reasoning}
                            isDark={isDark}
                            loading={isGenerating && !message.content}
                            expanded={isReasoningExpanded}
                            t={t}
                            onToggle={() => {
                                const newState = !isReasoningExpanded;
                                setReasoningExpanded(newState);
                                if (newState) {
                                    setSourcesExpanded(false);
                                    setRagExpanded(false);
                                }
                            }}
                        />
                    )}
                    {message.ragReferences && (message.ragReferences.length > 0 || message.ragReferencesLoading) && (
                        <RagReferencesChip
                            references={message.ragReferences || []}
                            loading={message.ragReferencesLoading}
                            isDark={isDark}
                            expanded={isRagExpanded}
                            onToggle={() => {
                                const newState = !isRagExpanded;
                                setRagExpanded(newState);
                                if (newState) {
                                    setReasoningExpanded(false);
                                    setSourcesExpanded(false);
                                }
                            }}
                        />
                    )}
                    {message.citations && message.citations.length > 0 && (
                        <SearchSourcesBlock
                            citations={message.citations}
                            isDark={isDark}
                            expanded={isSourcesExpanded}
                            t={t}
                            onToggle={() => {
                                const newState = !isSourcesExpanded;
                                setSourcesExpanded(newState);
                                if (newState) {
                                    setReasoningExpanded(false);
                                    setRagExpanded(false);
                                }
                            }}
                        />
                    )}
                </View>
            </View>

            {/* AI Reasoning Expanded Content (Below Header) */}
            {isReasoningExpanded && message.reasoning && (
                <Animated.View
                    entering={FadeInUp.duration(300)}
                    exiting={FadeOutUp.duration(200)}
                    className={`pl-4 border-l-2 ml-[38px] mb-4 my-1 ${isDark ? 'border-zinc-800' : 'border-gray-100'}`}
                >
                    <Typography
                        variant="caption"
                        selectable={true}
                        style={{
                            fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                            color: isDark ? '#a1a1aa' : '#64748b',
                            fontSize: 12,
                            lineHeight: 20
                        }}
                    >
                        {message.reasoning}
                    </Typography>
                </Animated.View>
            )}

            {/* Citations List (Below Header) */}
            {isSourcesExpanded && message.citations && (
                <Animated.View
                    entering={FadeInUp.duration(300)}
                    exiting={FadeOutUp.duration(200)}
                    className="ml-[38px] mb-4 space-y-2"
                >
                    {message.citations.map((citation, index) => (
                        <TouchableOpacity
                            key={index}
                            className={`flex-row items-start p-3 rounded-xl mb-2 ${isDark ? 'bg-zinc-900 border border-zinc-800' : 'bg-gray-50 border border-gray-100'}`}
                            onPress={() => Linking.openURL(citation.url)}
                        >
                            <View style={{ flex: 1 }}>
                                <Typography className="text-xs font-bold text-gray-900 dark:text-white mb-0.5" numberOfLines={1}>
                                    {citation.title}
                                </Typography>
                                <Typography className="text-[10px] text-gray-500" numberOfLines={1}>
                                    {citation.url}
                                </Typography>
                            </View>
                            <ExternalLink size={12} color={isDark ? '#9ca3af' : '#6b7280'} style={{ marginTop: 2 }} />
                        </TouchableOpacity>
                    ))}
                </Animated.View>
            )}

            {/* RAG References List (New Vertical Style) */}
            {isRagExpanded && message.ragReferences && (
                <View style={{ marginLeft: 38 }}>
                    <RagReferencesList
                        references={message.ragReferences}
                        isDark={isDark}
                    />
                </View>
            )}

            {/* ✅ ProcessingIndicator Details - 切片/摘要详情展开 */}
            {isProcessingExpanded && (
                <View style={{ marginLeft: 38, marginTop: 8 }}>
                    <ProcessingIndicatorDetails
                        isDark={isDark}
                        status={'idle'}
                        messageId={message.id}
                    />
                </View>
            )}

            {/* Main Content (No indentation) */}
            <View style={{ minHeight: 20 }}>
                {isWaitingForContent ? (
                    <View className="items-start py-2">
                        <LoadingDots isDark={isDark} color={agentColor} />
                    </View>
                ) : (
                    (!message.content && !message.reasoning) ? (
                        <View className="py-2">
                            <LoadingDots isDark={isDark} />
                        </View>
                    ) : (
                        <>
                            <Markdown
                                style={{
                                    body: {
                                        color: isDark ? '#E4E4E7' : '#27272A',
                                        fontSize: 16,
                                        lineHeight: 28,
                                    },
                                    code_inline: {
                                        backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                                        borderRadius: 4,
                                        paddingHorizontal: 4,
                                        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                        fontSize: 14,
                                        fontWeight: '500',
                                    },
                                    fence: {
                                        backgroundColor: isDark ? '#111' : '#f8fafc',
                                        borderColor: isDark ? '#27272a' : '#e2e8f0',
                                        borderWidth: 1,
                                        borderRadius: 16,
                                        marginVertical: 12,
                                        padding: 0,
                                    },
                                    blockquote: {
                                        backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                                        borderLeftWidth: 4,
                                        borderLeftColor: agentColor,
                                        paddingHorizontal: 16,
                                        paddingVertical: 12,
                                        borderRadius: 12,
                                        marginVertical: 12,
                                    },
                                    list_item: { marginVertical: 6 },
                                    bullet_list: { marginVertical: 10 },
                                    ordered_list: { marginVertical: 10 },
                                    heading1: { marginTop: 28, marginBottom: 14, fontWeight: '800', fontSize: 24, color: isDark ? '#fff' : '#000' },
                                    heading2: { marginTop: 24, marginBottom: 12, fontWeight: '700', fontSize: 20, color: isDark ? '#fff' : '#000' },
                                    heading3: { marginTop: 20, marginBottom: 10, fontWeight: '700', fontSize: 18, color: isDark ? '#fff' : '#000' },
                                    paragraph: { marginVertical: 10 },
                                }}
                                {...({ selectable: true } as any)}
                                rules={markdownRules}
                            >
                                {(() => {
                                    // Extract AI-generated images
                                    const { cleanContent, images } = extractImagesFromMarkdown(processedContent || '');
                                    // Store extracted images for rendering below
                                    (React as any)._aiImages = images;
                                    return cleanContent;
                                })()}
                            </Markdown>
                            {/* Render extracted AI-generated images */}
                            {!isUser && (() => {
                                const images = (React as any)._aiImages || [];
                                if (images.length === 0) return null;

                                return (
                                    <View style={{ marginTop: 12, gap: 12 }}>
                                        {images.map((img: { src: string; alt: string }, index: number) => (
                                            <GeneratedImage
                                                key={`ai-img-${index}`}
                                                src={img.src}
                                                alt={img.alt}
                                                isDark={isDark}
                                                t={t}
                                            />
                                        ))}
                                    </View>
                                );
                            })()}
                        </>
                    )
                )}
            </View>

            {/* Action Bar (Bottom Alignment) */}
            <View style={{
                marginTop: 8,
                borderTopWidth: StyleSheet.hairlineWidth,
                borderTopColor: isDark ? 'rgba(39, 39, 42, 0.5)' : '#f3f4f6',
                paddingTop: 4
            }}>
                <ActionBar
                    content={message.content || ''}
                    onDelete={onDelete}
                    onShare={handleShare}
                    onSelect={() => setModalVisible(true)}
                    onRegenerate={onRegenerate}
                    isDark={isDark}
                    isArchived={isArchived}  // ✅ 传递归档状态
                />
            </View>

            <SelectTextModal
                isVisible={isModalVisible}
                content={message.content || ''}
                onClose={() => setModalVisible(false)}
                isDark={isDark}
                t={t}
            />
        </Animated.View>
    );
};

// 使用 React.memo 优化性能：只在关键属性变化时重新渲染
export const ChatBubble = React.memo(ChatBubbleComponent, (prev, next) => {
    // 自定义比较函数：只有这些属性变化才重新渲染
    if (prev.message.id !== next.message.id) return false;
    if (prev.message.content !== next.message.content) return false;
    // @ts-ignore
    if (prev.message.reasoning !== next.message.reasoning) return false;
    // @ts-ignore
    if (prev.message.images !== next.message.images) return false;

    if (prev.agentColor !== next.agentColor) return false;
    if (prev.isGenerating !== next.isGenerating) return false;

    // 比较 citations（浅比较）
    // @ts-ignore
    const prevCitations = prev.message.citations || [];
    // @ts-ignore
    const nextCitations = next.message.citations || [];
    if (prevCitations.length !== nextCitations.length) return false;

    return true;
});
