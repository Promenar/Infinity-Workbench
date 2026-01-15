import React, { useState, useEffect, Component, useMemo } from 'react';
import {
  View,
  TouchableOpacity,
  ViewStyle,
  Platform,
  Linking,
  Text,
  Modal,
  TextInput,
  ScrollView,
  StyleSheet,
  Image,
  Dimensions,
  Pressable, // ✅ Import Pressable
} from 'react-native';
import { MemoryManager } from '../../../lib/rag/memory-manager';
import { graphExtractor } from '../../../lib/rag/graph-extractor';
import { RagOmniIndicator } from './RagOmniIndicator';
import { ContextManager } from '../utils/ContextManager';
import { useRagStore } from '../../../store/rag-store'; // ✅ 显式导入
import { Typography, ContextMenu } from '../../../components/ui';
import { useChatStore } from '../../../store/chat-store';
import { Message } from '../../../types/chat';
import { db } from '../../../lib/db'; // ✅ 导入db
import * as Clipboard from 'expo-clipboard';
import * as Haptics from '../../../lib/haptics';
import Markdown from 'react-native-markdown-display';
import { clsx } from 'clsx';
import { useTheme } from '../../../theme/ThemeProvider';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  withSequence,
  withRepeat,
  SharedValue,
  runOnJS,
  FadeIn,
  FadeOut,
  FadeInUp,
  FadeOutUp,
  LinearTransition,
} from 'react-native-reanimated';
import * as FileSystem from 'expo-file-system/legacy';
// import { SvgXml } from 'react-native-svg'; // Removed to prevent native crashes
import * as Sharing from 'expo-sharing';
import { captureRef } from 'react-native-view-shot';
import { AgentAvatar } from '../../../components/chat/AgentAvatar';
import { RagReferencesList } from './RagReferences';
import { ProcessingIndicatorDetails } from './ProcessingIndicator';
import { ToolExecutionTimeline } from '../../../components/skills/ToolExecutionTimeline';

import { findModelSpec } from '../../../lib/llm/model-utils';
import { ModelIconRenderer } from '../../../components/icons/ModelIconRenderer';
import {
  MathRenderer,
  LazySVGRenderer,
} from '../../../components/chat/MathRenderer';
import { extractImagesFromMarkdown } from '../utils/markdown-utils';
import { TaskMonitor } from './TaskMonitor';

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
  AlertCircle,
  FileInput,
  FileText,
  MoreHorizontal,
} from 'lucide-react-native';
import { ActivityIndicator } from 'react-native';
import { Colors } from '../../../theme/colors';


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
  onExtractGraph?: () => void;
  onVectorize?: () => void;
  onSummarize?: () => void;
  modelId?: string;
  modelName?: string; // ✅ 新增：友好的模型名称
  sessionId: string;
  onLayout?: (event: any) => void;
  isLastAssistantMessage?: boolean; // ✅ 新增：是否是最新的 AI 回复
}

// SVGErrorBoundary removed as we use WebView now
// 生成图片组件 - 独立组件避免在 Markdown rules 中使用 hooks
const GeneratedImage: React.FC<{ src: string; alt?: string; isDark: boolean; t: any }> = React.memo(
  ({ src, alt, isDark, t }) => {
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
        <View
          style={{
            width: '100%',
            minHeight: 200,
            backgroundColor: isDark ? '#27272a' : '#f4f4f5',
            borderRadius: 12,
            overflow: 'hidden',
            position: 'relative',
            justifyContent: 'center',
            alignItems: 'center',
            borderWidth: 1,
            borderColor: isDark ? '#3f3f46' : '#e4e4e7',
          }}
        >
          {isLoading && (
            <View style={{ position: 'absolute', zIndex: 10 }}>
              <ActivityIndicator size="large" color={isDark ? '#a1a1aa' : '#6b7280'} />
            </View>
          )}

          {hasError ? (
            <View style={{ alignItems: 'center', padding: 20 }}>
              <AlertCircle size={32} color="#ef4444" />
              <Typography
                variant="caption"
                style={{ color: '#ef4444', marginTop: 8, textAlign: 'center' }}
              >
                {t.agent.imageLoadError}
              </Typography>
              <Typography
                variant="caption"
                style={{ color: isDark ? '#71717a' : '#a1a1aa', marginTop: 4, fontSize: 11 }}
              >
                {src.startsWith('data:') ? t.agent.imageTooLarge : t.agent.imagePathError}
              </Typography>
            </View>
          ) : (
            <Image
              source={{ uri: src }}
              style={{
                width: '100%',
                height: dimensions
                  ? (dimensions.height / dimensions.width) * Dimensions.get('window').width * 0.9
                  : 300,
                maxHeight: 600,
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
                borderColor: 'rgba(255,255,255,0.3)',
              }}
            >
              <Download size={18} color="#fff" />
            </TouchableOpacity>
          )}
        </View>
      </View>
    );
  },
);

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
          withTiming(0.3, { duration: 500 }),
        ),
        -1,
        true,
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
    marginHorizontal: 2,
  };

  const anim1 = useAnimatedStyle(() => ({ opacity: opacity1.value }));
  const anim2 = useAnimatedStyle(() => ({ opacity: opacity2.value }));
  const anim3 = useAnimatedStyle(() => ({ opacity: opacity3.value }));

  return (
    <View className="flex-row items-center justify-center p-2 mb-2" style={{ height: 24 }}>
      <Animated.View style={[dotStyle, anim1]} />
      <Animated.View style={[dotStyle, anim2]} />
      <Animated.View style={[dotStyle, anim3]} />
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
  const { colors } = useTheme();
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
          <TouchableOpacity activeOpacity={1} onPress={onClose} style={{ flex: 1 }} />
        </Animated.View>

        <Animated.View
          style={[
            {
              width: '100%',
              height: '80%',
              backgroundColor: isDark ? '#18181b' : '#ffffff',
              borderTopLeftRadius: 24,
              borderTopRightRadius: 24,
              padding: 24,
              paddingBottom: Platform.OS === 'ios' ? 40 : 24,
            },
            contentStyle,
          ]}
        >
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 16,
            }}
          >
            <TouchableOpacity onPress={onClose} className="p-2">
              <X size={24} color={isDark ? '#e4e4e7' : '#27272a'} />
            </TouchableOpacity>
            <Typography className="text-base font-bold">{t.agent.viewAndSelectText}</Typography>
            <TouchableOpacity onPress={handleCopy} style={{ backgroundColor: colors[500] }} className="p-2 rounded-full">
              <Copy size={20} color="#fff" />
            </TouchableOpacity>
          </View>

          <Typography variant="caption" className="mb-4 text-gray-500">
            {t.agent.textSelectionHint}
          </Typography>

          <ScrollView className="flex-1" showsVerticalScrollIndicator={false}>
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

/**
 * MessageMeta - 消息元信息组件
 * 显示模型名称和时间戳，替代原有的 ActionBar
 * 设计原则：隐式注脚，不喧宾夺主
 */
const MessageMeta: React.FC<{
  modelName?: string;
  timestamp?: number;
  isDark: boolean;
}> = ({ modelName, timestamp, isDark }) => {
  // 智能时间显示：同天只显示时间，跨天显示日期+时间
  const getTimeStr = (ts: number) => {
    const date = new Date(ts);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();

    if (isToday) {
      return date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
      });
    } else {
      // 跨天：显示 MM/DD HH:mm
      return date.toLocaleDateString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
      }) + ' ' + date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
      });
    }
  };

  const timeStr = timestamp ? getTimeStr(timestamp) : '';

  return (
    <View style={{
      flexDirection: 'row',
      alignItems: 'center',
      marginTop: 4, // 减少间距：8 -> 4
      gap: 6, // 减少间距：8 -> 6
      opacity: 0.6, // 隐式设计
    }}>
      {modelName && (
        <Text style={{
          fontSize: 11,
          color: isDark ? '#71717a' : '#a1a1aa',
          fontWeight: '400',
          textTransform: 'uppercase', // 大写字母
          letterSpacing: 0.5, // 增加字间距提升可读性
        }}>
          {modelName}
        </Text>
      )}
      {modelName && timeStr && (
        <Text style={{ fontSize: 11, color: isDark ? '#52525b' : '#d4d4d8' }}>·</Text>
      )}
      {timeStr && (
        <Text style={{
          fontSize: 11,
          color: isDark ? '#52525b' : '#d4d4d8',
        }}>
          {timeStr}
        </Text>
      )}
    </View>
  );
};

const ImageViewerModal = ({
  visible,
  uri,
  onClose,
}: {
  visible: boolean;
  uri: string;
  onClose: () => void;
}) => {
  return (
    <Modal visible={visible} transparent={true} animationType="fade" onRequestClose={onClose}>
      <View
        style={{
          flex: 1,
          backgroundColor: 'black',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
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
  onExtractGraph, // ✅ 新增
  onVectorize, // ✅ 新增
  onSummarize, // ✅ 新增
  isGenerating,
  modelId,
  modelName, // ✅ 新增：友好模型名称
  sessionId,
  onLayout, // ✅ 新增：传递布局回调
  isLastAssistantMessage, // ✅ 新增：是否最新 AI 回复
}) => {
  const { t } = useI18n();

  const { isDark, colors } = useTheme();
  const { processingState, updateProcessingState } = useRagStore();
  const sessionData = useChatStore(
    React.useCallback((state) => state.sessions.find((s) => s.id === sessionId), [sessionId]),
  );
  const approvalRequest = sessionData?.approvalRequest;
  const isWaitingForApproval = sessionData?.loopStatus === 'waiting_for_approval';
  const isIntervened = !!sessionData?.pendingIntervention;

  const isUser = message.role === 'user';
  // const { isDark } = useTheme(); // REMOVED to avoid context crash during unmount

  // Determine avatar source
  const modelSpec = modelId
    ? findModelSpec(modelId)
    : message.modelId
      ? findModelSpec(message.modelId)
      : null;

  // All hooks must be at top level - moved from isUser conditional block
  const [isModalVisible, setModalVisible] = useState(false);

  // Determine if message is "fresh" (less than 1s old) to prevent animation replay on scroll
  const isRecent = React.useMemo(() => {
    return Date.now() - message.createdAt < 1000;
  }, [message.createdAt]);

  const [viewImageUri, setViewImageUri] = useState<string | null>(null);
  const [bubbleWidth, setBubbleWidth] = useState(0);
  const bubbleRef = React.useRef<View>(null);

  // Moved from below to fix Hooks order error

  const [isRagExpanded, setRagExpanded] = useState(false);
  const [isProcessingExpanded, setProcessingExpanded] = useState(false); // ✅ 新增：ProcessingIndicator展开状态
  const [isArchived, setIsArchived] = useState(message.isArchived || false); // ✅ 归档状态

  // Determine processing state from store status if available
  // Refined Logic:
  // 1. If explicit status is 'processing', showing spinner.
  // 2. If no status, but message is recent (< 1 min) AND AI message AND not generating, show spinner (fallback for gap between generation and status update).
  // 3. User messages should NOT show spinner unless explicitly processing (e.g. uploading/vectorizing).
  const isProcessing =
    message.vectorizationStatus === 'processing' ||
    (!isUser && !isGenerating && !message.isArchived && !message.vectorizationStatus && Date.now() - message.createdAt < 60000);

  // Sync archive status
  useEffect(() => {
    // 1. Force false if generating or processing or error
    if (isGenerating || isProcessing || message.vectorizationStatus === 'error' || message.vectorizationStatus === 'processing') {
      setIsArchived(false);
      return;
    }

    // 2. Trust explicit success
    if (message.vectorizationStatus === 'success') {
      setIsArchived(true);
      return;
    }

    // 3. Trust legacy prop (only if not very new, to avoid optimistic glitches)
    if (message.isArchived) {
      setIsArchived(true);
      return;
    }

    // 4. DB Check (Fallback)
    // Only check if message is old enough (> 2s) to avoid race conditions with creation
    if (Date.now() - message.createdAt < 2000) return;

    const checkArchiveStatus = async () => {
      if (message.role === 'system') return;
      try {
        const result = await db.execute(
          'SELECT 1 FROM vectors WHERE (start_message_id = ? OR end_message_id = ?) AND session_id = ? LIMIT 1',
          [message.id, message.id, sessionId],
        );
        const hasRecord =
          result.rows &&
          ((result.rows as any)._array?.length > 0 ||
            (result.rows as any).length > 0 ||
            (result.rows as any)[0]);
        if (hasRecord) setIsArchived(true);
      } catch (e) {
        console.error('[ChatBubble] Failed to check archive status:', e);
      }
    };
    checkArchiveStatus();
  }, [message.id, sessionId, message.isArchived, message.vectorizationStatus, isGenerating, isProcessing]);

  // Auto-collapse reasoning when done


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
  const markdownRules = useMemo(
    () => ({
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
        if (
          language === 'svg' ||
          content.startsWith('<svg') ||
          (content.includes('<svg') && content.includes('</svg>'))
        ) {
          // 1. 语法预检：捕获底层库必然崩溃的模式 (dM, fill#, fillred 等)
          const hasObviousSyntaxErrors =
            /<path[^>]*\s+d[A-Z0-9]/.test(content) ||
            /<rect[^>]*\s+x[A-Z0-9]/.test(content) ||
            content.includes('strokeM') ||
            content.includes('stroke#') ||
            (content.includes('<path') && !content.includes('d='));

          if (hasObviousSyntaxErrors) {
            return (
              <View
                key={node.key}
                collapsable={false}
                style={{
                  marginVertical: 12,
                  padding: 16,
                  backgroundColor: isDark ? '#27272a' : '#fff1f2',
                  borderRadius: 12,
                  borderWidth: 1,
                  borderColor: isDark ? '#3f3f46' : '#fecaca',
                }}
              >
                <Typography
                  selectable={true}
                  style={{ color: '#e11d48', fontSize: 13, fontWeight: '700' }}
                >
                  {t.svgErrorTitle}
                </Typography>
                <Typography
                  selectable={true}
                  variant="caption"
                  style={{ color: isDark ? '#a1a1aa' : '#6b7280', marginTop: 4 }}
                >
                  {t.svgBlockedDesc}
                </Typography>
              </View>
            );
          }

          // 检测是否包含动画标签（CSS 动画、SMIL 动画）
          // 恢复动画检测：交由 InteractiveSVGRenderer 处理（默认静态，点击播放）
          // 检测是否包含动画标签（CSS 动画、SMIL 动画）
          // 统一使用 LazySVGRenderer 处理所有 SVG，包括动画和静态
          // 移除了手动检测动画逻辑，全部通过 LazySVGRenderer 实现懒加载和全屏

          return (
            <View
              key={node.key + '-svg'}
              collapsable={false}
              style={{ marginVertical: 12, width: '100%' }}
            >
              <LazySVGRenderer svgContent={content} isDark={isDark} />
            </View>
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
            <View
              style={{
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
                paddingHorizontal: 12,
                paddingVertical: 8,
                backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.03)',
                borderBottomWidth: StyleSheet.hairlineWidth,
                borderBottomColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)',
              }}
            >
              <Typography
                variant="caption"
                style={{ color: isDark ? '#a1a1aa' : '#71717a', fontWeight: '600' }}
              >
                {language.toUpperCase() || 'CODE'}
              </Typography>
              <TouchableOpacity
                onPress={handleCopyCode}
                hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
              >
                <Copy size={14} color={isDark ? '#a1a1aa' : '#71717a'} />
              </TouchableOpacity>
            </View>
            <View style={{ padding: 12 }}>
              <Text
                selectable={true}
                style={{
                  fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                  fontSize: 13,
                  color: isDark ? '#ffffff' : '#27272a',
                }}
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
      // ✅ Allow inline text + math mixing
      paragraph: (node: any, children: any, parent: any, styles: any) => (
        <View
          key={node.key}
          style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', marginVertical: 8 }}
        >
          {children}
        </View>
      ),
      // ✅ Detect Inline Math ($...$)
      text: (node: any, children: any, parent: any, styles: any) => {
        const content = node.content;
        // Simple optimization: check for possible math delimiter before splitting
        if (!content.includes('$')) {
          return <Text key={node.key} style={styles.text}>{content}</Text>;
        }

        const parts = content.split(/(\$[^\$]+\$)/g);

        return (
          <React.Fragment key={node.key}>
            {parts.map((part: string, index: number) => {
              if (part.startsWith('$') && part.endsWith('$')) {
                // Remove $ delimiters
                const math = part.slice(1, -1);
                return (
                  <View key={index} style={{ marginHorizontal: 2 }}>
                    <MathRenderer content={math} isBlock={false} />
                  </View>
                );
              }
              // Skip empty text parts (e.g. caused by split at start/end)
              if (!part) return null;

              return (
                <Text key={index} style={styles.text}>
                  {part}
                </Text>
              );
            })}
          </React.Fragment>
        );
      },
    }),
    [isDark],
  );

  // 🧐 UI 优化：隐藏并彻底不渲染工具消息
  // 🔑 必须放在所有 Hook 之后以满足 React 渲染准则
  if (message.role === 'tool') return null;

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
        style={{
          flexDirection: 'row',
          justifyContent: 'flex-end',
          marginBottom: 20, // Reduced 32 -> 20
          width: '100%',
          paddingHorizontal: 20, // Keep padding horizontal
        }}
      >
        <View style={{ maxWidth: '80%' }}>
          <ContextMenu
            items={[
              {
                label: '复制内容', // 4字
                icon: <Copy />,
                onPress: () => {
                  Clipboard.setStringAsync(message.content);
                  Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                },
              },
              {
                label: '选择文本', // 4字
                icon: <Type />,
                onPress: () => setModalVisible(true),
              },
              {
                label: '分享消息', // 4字
                icon: <Share2 />,
                onPress: handleShare,
              },
              onResend && {
                label: '重新发送', // 4字
                icon: <RefreshCw />,
                onPress: () => {
                  setTimeout(() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    onResend?.();
                  }, 10);
                },
              },
              {
                label: '删除消息', // 4字
                icon: <Trash2 />,
                destructive: true,
                onPress: () => {
                  Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
                  onDelete?.();
                },
              },
            ].filter(Boolean) as any}
          >
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
                    fontSize: 15,
                    lineHeight: 24,
                    fontWeight: '600',
                    letterSpacing: 0.2,
                    textAlign: 'left',
                  },
                  text: {
                    color: isDark ? '#ffffff' : '#18181b',
                    fontSize: 15,
                    lineHeight: 24,
                    fontWeight: '600',
                  },
                  paragraph: { marginVertical: 0, paddingVertical: 0, textAlign: 'left' },
                  blockquote: {
                    backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                    borderLeftWidth: 3,
                    borderLeftColor: colors[500],
                    paddingHorizontal: 12,
                    paddingVertical: 8,
                    borderRadius: 8,
                    marginVertical: 8,
                  },
                }}
                {...({ selectable: true } as any)}
              >
                {processedContent || ''}
              </Markdown>
              {message.images && message.images.length > 0 && (
                <View
                  style={{
                    flexDirection: 'row',
                    flexWrap: 'wrap',
                    marginTop: message.content ? 8 : 0,
                    gap: 4,
                  }}
                >
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
          </ContextMenu>

          <View
            style={{
              marginTop: 4, // 减少间距：8 -> 4
              borderTopWidth: StyleSheet.hairlineWidth,
              borderTopColor: isDark ? 'rgba(39, 39, 42, 0.5)' : '#f3f4f6',
              paddingTop: 2, // 减少间距：4 -> 2
              alignItems: 'flex-end',
              width: '100%',
            }}
          >
            <MessageMeta
              timestamp={message.createdAt}
              isDark={isDark}
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
      style={{ marginBottom: 24, width: '100%', paddingHorizontal: 20 }} // Reduced 40 -> 24
      ref={bubbleRef}
      collapsable={false}
      onLayout={onLayout} // ✅ 传递 onLayout
    >
      {/* Header Row: Avatar & Status Chips */}
      <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
        <View
          style={{
            backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#ffffff',
            borderRadius: 9999,
            padding: 2,
            borderWidth: 1,
            borderColor: isDark ? 'rgba(255,255,255,0.08)' : '#f3f4f6',
            shadowColor: '#000',
            shadowOffset: { width: 0, height: 1 },
            shadowOpacity: 0.05,
            shadowRadius: 2,
            elevation: 1,
          }}
        >
          <AgentAvatar
            id={agentId || 'ai'}
            name={agentName || 'AI'}
            avatar={agentAvatar || 'Sparkles'}
            color={agentColor}
            size={28}
          />
        </View>

        <View className="flex-1 ml-3">
          {(!!message.ragReferencesLoading ||
            (Array.isArray(message.ragReferences) && message.ragReferences.length > 0) ||
            (processingState.activeMessageId === message.id && (processingState.status !== 'idle' || processingState.kgStatus !== 'idle')) ||
            (processingState.activeMessageId === message.id && processingState.pulseActive) ||
            (isGenerating && processingState.activeMessageId === message.id) || // 🔑 即使结果为 0，生成期间也要保活
            (!!message.ragProgress) // 🔑 长效存留：只要 RAG 曾经运行过，就保留指示器以显示历史结果
          ) && (
              <View style={{ width: '100%', marginBottom: 4 }}>
                <RagOmniIndicator
                  messageId={message.id}
                  isGenerating={isGenerating}
                  referencesCount={message.ragReferences?.length || 0}
                  isExpanded={isRagExpanded}
                  onToggle={() => setRagExpanded(!isRagExpanded)}
                />
              </View>
            )}
        </View>
      </View>



      {/* RAG References List (New Vertical Style) */}
      {isRagExpanded && message.ragReferences && (
        <View style={{ marginLeft: 38 }}>
          <RagReferencesList references={message.ragReferences} isDark={isDark} />
        </View>
      )}

      {/* ✅ ProcessingIndicator Details - 切片/摘要详情展开 */}
      {isProcessingExpanded && (
        <View style={{ marginLeft: 38, marginTop: 8 }}>
          <ProcessingIndicatorDetails isDark={isDark} status={'idle'} messageId={message.id} />
        </View>
      )}

      {!isUser && (message.planningTask || (isGenerating && sessionData?.activeTask)) && (
        <TaskMonitor
          sessionId={sessionId}
          task={message.planningTask || sessionData?.activeTask}
          containerStyle={{
            marginLeft: -15,
            marginRight: -12, // Exact match with Timeline (-12)
            marginTop: 2,
            marginBottom: 4
          }}
        />
      )}

      {/* ✅ Agentic Loop Timeline (Aligned with Avatar Center) */}
      {message.executionSteps && message.executionSteps.length > 0 && (
        <View style={{ marginLeft: 5, marginRight: -12, marginBottom: 4 }}>
          <ToolExecutionTimeline steps={message.executionSteps} isMessageGenerating={isGenerating} sessionId={sessionId} />
        </View>
      )}

      {/* Main Content (No indentation) */}
      <ContextMenu
        items={[
          {
            label: '复制内容', // 4字
            icon: <Copy />,
            onPress: () => {
              Clipboard.setStringAsync(message.content);
              Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
            },
          },
          {
            label: '选择文本', // 4字
            icon: <Type />,
            onPress: () => setModalVisible(true),
          },
          {
            label: '分享消息', // 4字
            icon: <Share2 />,
            onPress: handleShare,
          },
          // 重新生成只在最新 AI 回复上显示
          isLastAssistantMessage && onRegenerate && {
            label: '重新生成', // 4字
            icon: <RefreshCw />,
            onPress: () => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                onRegenerate?.();
              }, 10);
            },
          },
          {
            label: '知识图谱', // 4字
            icon: <BrainCircuit />,
            onPress: () => {
              onExtractGraph?.();
            },
          },
          {
            label: '手动向量', // 4字
            icon: <FileInput />,
            onPress: () => {
              onVectorize?.();
            },
          },
          {
            label: '触发摘要', // 4字
            icon: <FileText />,
            onPress: () => {
              onSummarize?.();
            },
          },
          {
            label: '删除消息', // 4字
            icon: <Trash2 />,
            destructive: true,
            onPress: () => {
              Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
              onDelete?.();
            },
          },
        ].filter(Boolean) as any}
      >
        <View style={{ minHeight: 20 }}>
          {isWaitingForContent ? (
            <View className="items-start py-2">
              <LoadingDots isDark={isDark} color={agentColor} />
            </View>
          ) : (!isUser && !message.content && !message.reasoning && isGenerating) ? (
            <View className="py-2">
              <LoadingDots isDark={isDark} />
            </View>
          ) : (
            <>
              <Markdown
                style={{
                  body: {
                    color: isDark ? Colors.dark.textPrimary : '#27272A',
                    fontSize: 15, // Reduced 16 -> 15
                    lineHeight: 26, // Reduced 28 -> 26
                  },
                  text: {
                    color: isDark ? Colors.dark.textPrimary : '#27272A',
                    fontSize: 15,
                    lineHeight: 26,
                  },
                  code_inline: {
                    backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                    borderRadius: 4,
                    paddingHorizontal: 4,
                    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                    fontSize: 13, // Reduced 14 -> 13
                    fontWeight: '500',
                  },
                  fence: {
                    backgroundColor: isDark ? '#080911' : '#f8fafc',
                    borderColor: isDark ? Colors.dark.borderDefault : '#e2e8f0',
                    borderWidth: 1,
                    borderRadius: 12, // Reduced 16 -> 12
                    marginVertical: 8, // Reduced 12 -> 8
                    padding: 0,
                  },
                  blockquote: {
                    backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                    borderLeftWidth: 3, // Reduced 4 -> 3
                    borderLeftColor: agentColor,
                    paddingHorizontal: 12, // Reduced 16 -> 12
                    paddingVertical: 8, // Reduced 12 -> 8
                    borderRadius: 8, // Reduced 12 -> 8
                    marginVertical: 8, // Reduced 12 -> 8
                  },
                  list_item: { marginVertical: 4 }, // Reduced 6 -> 4
                  bullet_list: { marginVertical: 6 }, // Reduced 10 -> 6
                  ordered_list: { marginVertical: 6 }, // Reduced 10 -> 6
                  heading1: {
                    marginTop: 20, // Reduced 28 -> 20
                    marginBottom: 10, // Reduced 14 -> 10
                    fontWeight: '800',
                    fontSize: 22, // Reduced 24 -> 22
                    color: isDark ? '#fff' : '#000',
                  },
                  heading2: {
                    marginTop: 18, // Reduced 24 -> 18
                    marginBottom: 8, // Reduced 12 -> 8
                    fontWeight: '700',
                    fontSize: 18, // Reduced 20 -> 18
                    color: isDark ? '#fff' : '#000',
                  },
                  heading3: {
                    marginTop: 14, // Reduced 20 -> 14
                    marginBottom: 6, // Reduced 10 -> 6
                    fontWeight: '700',
                    fontSize: 16, // Reduced 18 -> 16
                    color: isDark ? '#fff' : '#000',
                  },
                  paragraph: { marginVertical: 6 }, // Reduced 10 -> 6
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
              {!isUser &&
                (() => {
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
          )}
        </View>
      </ContextMenu>

      {/* Message Meta (模型名称 + 时间戳) */}
      <View
        style={{
          marginTop: 4, // 减少间距：8 -> 4
          borderTopWidth: StyleSheet.hairlineWidth,
          borderTopColor: isDark ? 'rgba(255, 255, 255, 0.05)' : '#f3f4f6',
          paddingTop: 2, // 减少间距：4 -> 2
        }}
      >
        <MessageMeta
          modelName={modelName}
          timestamp={message.createdAt}
          isDark={isDark}
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

  // ✅ Check Status Changes
  // @ts-ignore
  if (prev.message.vectorizationStatus !== next.message.vectorizationStatus) return false;
  // @ts-ignore
  if (prev.message.isArchived !== next.message.isArchived) return false;

  // 比较 citations（浅比较）
  // @ts-ignore
  const prevCitations = prev.message.citations || [];
  // @ts-ignore
  const nextCitations = next.message.citations || [];
  if (prevCitations.length !== nextCitations.length) return false;

  // Check RAG References and Metadata
  // @ts-ignore
  if (prev.message.ragReferencesLoading !== next.message.ragReferencesLoading) return false;
  // @ts-ignore
  if (prev.message.ragMetadata !== next.message.ragMetadata) return false;
  // @ts-ignore
  const prevRagRefs = prev.message.ragReferences || [];
  // @ts-ignore
  const nextRagRefs = next.message.ragReferences || [];
  if (prevRagRefs.length !== nextRagRefs.length) return false;

  // @ts-ignore
  if (prev.message.executionSteps !== next.message.executionSteps) return false;
  // @ts-ignore
  if (prev.message.planningTask !== next.message.planningTask) return false;

  return true;
});
