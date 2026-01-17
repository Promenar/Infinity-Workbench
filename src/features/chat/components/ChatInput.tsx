import React, { useState, useEffect } from 'react';
import {
  View,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Platform,
  Image,
  ScrollView,
  Modal,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  ArrowUp,
  Plus,
  Cpu,
  Square,
  Calculator,
  X,
  Image as ImageIcon,
  Camera,
  Zap,
  Shield,
  PlayCircle,
  Check,
  Wrench, // New Icon for tools
} from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { useI18n } from '../../../lib/i18n';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
import { BlurView } from 'expo-blur';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
  cancelAnimation,
} from 'react-native-reanimated';
import { useTheme } from '../../../theme/ThemeProvider';
import { Typography, ConfirmDialog, GlassBottomSheet } from '../../../components/ui';
import Svg, { Circle } from 'react-native-svg';
import { TokenUsage } from '../../../types/chat';
import { formatTokenCount } from '../utils/token-counter'
import { useChatStore } from '../../../store/chat-store';
import { isForcedReasoningModel } from '../../../lib/llm/model-utils';
import { useApiStore } from '../../../store/api-store';
import { ANIMATION_DURATION } from '../../../theme/animations';

// ✅ 内联执行模式按钮（适配输入栏风格）
const ExecutionModeButton = ({ sessionId, isDark }: { sessionId: string; isDark: boolean }) => {
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const setExecutionMode = useChatStore((s) => s.setExecutionMode);
  const [visible, setVisible] = useState(false);

  if (!session) return null;
  const mode = session.executionMode || 'semi';

  const getIcon = (m: string, size: number = 14) => {
    switch (m) {
      case 'auto':
        return <Zap size={size} color="#6366f1" strokeWidth={2.5} />;
      case 'semi':
        return <Shield size={size} color="#d97706" strokeWidth={2.5} />;
      case 'manual':
        return <PlayCircle size={size} color="#059669" strokeWidth={2.5} />;
      default:
        return <Zap size={size} color={isDark ? '#52525b' : '#a1a1aa'} />;
    }
  };

  const getLabel = (m: string) => {
    switch (m) {
      case 'auto':
        return 'AUTO';
      case 'semi':
        return 'SEMI';
      case 'manual':
        return 'MANU';
      default:
        return m.toUpperCase();
    }
  };

  const handleSelect = (m: 'auto' | 'semi' | 'manual') => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      setExecutionMode(sessionId, m);
      setVisible(false);
    }, 10);
  };

  return (
    <>
      <TouchableOpacity
        onPress={() => {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
          setVisible(true);
        }}
        activeOpacity={0.6}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 8,
          paddingVertical: 2,
          borderRadius: 10,
          backgroundColor: 'rgba(0,0,0,0.03)',
          gap: 4,
        }}
      >
        {getIcon(mode, 10)}
        <Typography
          className="text-[9px] font-black uppercase tracking-tight"
          style={{
            color: mode === 'auto' ? '#6366f1' : mode === 'semi' ? '#d97706' : '#059669',
          }}
        >
          {getLabel(mode)}
        </Typography>
      </TouchableOpacity>

      <GlassBottomSheet
        visible={visible}
        onClose={() => setVisible(false)}
        title="执行模式"
        subtitle="选择 AI 的任务执行策略"
        height="auto"
      >
        <View style={{ paddingHorizontal: 16, paddingBottom: 24, gap: 8 }}>
          {(['auto', 'semi', 'manual'] as const).map((m) => {
            const isSelected = mode === m;
            return (
              <TouchableOpacity
                key={m}
                onPress={() => handleSelect(m)}
                activeOpacity={0.7}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  padding: 14,
                  borderRadius: 20,
                  backgroundColor: isSelected
                    ? isDark
                      ? 'rgba(255, 255, 255, 0.08)'
                      : 'rgba(0, 0, 0, 0.04)'
                    : 'transparent',
                  borderWidth: 1,
                  borderColor: isSelected
                    ? isDark
                      ? 'rgba(255, 255, 255, 0.12)'
                      : 'rgba(0, 0, 0, 0.08)'
                    : 'transparent',
                  gap: 14,
                }}
              >
                <View
                  style={{
                    padding: 10,
                    borderRadius: 12,
                    backgroundColor:
                      m === 'auto'
                        ? 'rgba(99, 102, 241, 0.12)'
                        : m === 'semi'
                          ? 'rgba(217, 119, 6, 0.12)'
                          : 'rgba(16, 185, 129, 0.12)',
                  }}
                >
                  {getIcon(m, 22)}
                </View>
                <View style={{ flex: 1 }}>
                  <Typography style={{ fontSize: 16, fontWeight: '700', color: isDark ? '#fff' : '#111' }}>
                    {m === 'auto' ? '自动' : m === 'semi' ? '半自动' : '手动'}
                  </Typography>
                  <Typography
                    variant="caption"
                    style={{ color: isDark ? 'rgba(255,255,255,0.5)' : 'rgba(0,0,0,0.5)', marginTop: 2 }}
                  >
                    {m === 'auto' ? '连续运行' : m === 'semi' ? '高风险操作暂停' : '每步需确认'}
                  </Typography>
                </View>
                {isSelected && (
                  <View
                    style={{
                      width: 24,
                      height: 24,
                      borderRadius: 12,
                      backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <Check size={14} color={isDark ? '#fff' : '#000'} strokeWidth={3} />
                  </View>
                )}
              </TouchableOpacity>
            );
          })}
        </View>
      </GlassBottomSheet>
    </>
  );
};

interface ChatInputProps {
  onSendMessage: (
    text: string,
    options?: { webSearch?: boolean; reasoning?: boolean; images?: string[] },
  ) => void;
  onStop?: () => void;
  sessionId: string;
  disabled?: boolean;
  loading?: boolean;
  agentColor?: string;
  currentModel?: string;
  onModelPress?: () => void;
  tokenUsage?: {
    total: number;
    last?: TokenUsage;
  };
  onTokenPress?: () => void;
  isInterventionMode?: boolean;
  // ✅ 新增：重发编辑模式
  editingMessageId?: string;      // 正在编辑的消息 ID
  initialEditText?: string;       // 初始编辑内容
  onCancelEdit?: () => void;      // 取消编辑回调
  toolsEnabled?: boolean;         // New prop
  onToggleTools?: () => void;     // New prop
}

const AnimatedCircle = Animated.createAnimatedComponent(Circle);

export function ChatInput({
  onSendMessage,
  onStop,
  sessionId,
  disabled,
  loading,
  agentColor = '#6366f1',
  currentModel,
  onModelPress,
  tokenUsage,
  onTokenPress,
  isInterventionMode,
  editingMessageId,
  initialEditText,
  onCancelEdit,
  toolsEnabled = true, // Default to true
  onToggleTools,
}: ChatInputProps) {
  const { t } = useI18n();
  const { isDark, colors } = useTheme();
  const rotation = useSharedValue(0);
  const [text, setText] = useState('');
  const [selectedImages, setSelectedImages] = useState<string[]>([]);
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);

  // ✅ 编辑模式：当进入编辑模式时，设置初始文本
  useEffect(() => {
    if (editingMessageId && initialEditText !== undefined) {
      setText(initialEditText);
    }
  }, [editingMessageId, initialEditText]);

  // 确认弹窗状态
  const [confirmState, setConfirmState] = useState<{
    visible: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
  }>({
    visible: false,
    title: '',
    message: '',
    onConfirm: () => { },
  });

  // Access store directly to persist toggles
  const session = useChatStore((state) => state.getSession(sessionId));
  const updateSessionOptions = useChatStore((state) => state.updateSessionOptions);
  const updateSessionDraft = useChatStore((state) => state.updateSessionDraft);

  const webSearchEnabled = session?.options?.webSearch ?? false; // Default to false
  const reasoningEnabled = session?.options?.reasoning ?? true; // Default to true

  // Load draft on mount
  useEffect(() => {
    if (session?.draft) {
      setText(session.draft);
    }
  }, [sessionId]); // Only run when sessionId changes (effectively on mount for this component instance)

  // Save draft on text change (debounced)
  useEffect(() => {
    const timer = setTimeout(() => {
      if (session && text !== session.draft) {
        // Only update if changed to avoid loop
        // Don't save empty string if it was already undefined/empty to avoid unnecessary writes
        if (text || session.draft) {
          updateSessionDraft(sessionId, text || undefined);
        }
      }
    }, 500);

    return () => clearTimeout(timer);
  }, [text, sessionId]);

  // 检测是否为强制推理模型（无法关闭推理）
  const providers = useApiStore((state) => state.providers);
  const modelId = session?.modelId;
  const currentModelConfig = modelId
    ? providers.flatMap((p) => p.models).find((m) => m.uuid === modelId || m.id === modelId)
    : undefined;
  const isModelForcedReasoning = currentModelConfig
    ? isForcedReasoningModel(currentModelConfig.id)
    : false;

  useEffect(() => {
    if (loading) {
      rotation.value = withRepeat(
        withTiming(360, { duration: ANIMATION_DURATION.ROTATION_SLOW, easing: Easing.linear }),
        -1,
      );
    } else {
      cancelAnimation(rotation);
      rotation.value = 0;
    }
  }, [loading]);

  const animatedCircleStyle = useAnimatedStyle(() => {
    return {
      transform: [{ rotate: `${rotation.value}deg` }],
    };
  });

  const handleSend = () => {
    if (loading && onStop) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        onStop();
      }, 0);
      return;
    }

    if ((!text.trim() && selectedImages.length === 0) || disabled || loading) return;

    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      // Pass the current persistent options
      onSendMessage(text, {
        webSearch: webSearchEnabled,
        reasoning: reasoningEnabled,
        images: selectedImages.length > 0 ? selectedImages : undefined,
      });
      setText('');
      updateSessionDraft(sessionId, undefined); // Clear draft immediately
      setSelectedImages([]);
    }, 0);
  };

  const handlePickImage = async (source: 'camera' | 'library') => {
    setShowAttachmentMenu(false);
    try {
      let result;
      if (source === 'camera') {
        const { status } = await ImagePicker.requestCameraPermissionsAsync();
        if (status !== 'granted') {
          setConfirmState({
            visible: true,
            title: t.chat.cameraPermission,
            message: t.chat.cameraPermissionMessage,
            onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
          });
          return;
        }
        result = await ImagePicker.launchCameraAsync({
          mediaTypes: ImagePicker.MediaTypeOptions.Images,
          allowsEditing: false,
          quality: 0.7,
        });
      } else {
        const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
        if (status !== 'granted') {
          setConfirmState({
            visible: true,
            title: t.chat.galleryPermission,
            message: t.chat.galleryPermissionMessage,
            onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
          });
          return;
        }
        result = await ImagePicker.launchImageLibraryAsync({
          mediaTypes: ImagePicker.MediaTypeOptions.Images,
          allowsMultipleSelection: true,
          quality: 0.7,
        });
      }

      if (!result.canceled) {
        const newImages: string[] = [];
        // Ensure directory exists - using makeDirectoryAsync with intermediates: true handles existence check
        const imgDir = (FileSystem.documentDirectory || '') + 'images/';
        await FileSystem.makeDirectoryAsync(imgDir, { intermediates: true });

        for (const asset of result.assets) {
          const filename = `img_${Date.now()}_${Math.random().toString(36).slice(2)}.jpg`;
          const dest = imgDir + filename;
          await FileSystem.copyAsync({
            from: asset.uri,
            to: dest,
          });
          newImages.push(dest);
        }

        if (newImages.length > 0) {
          setSelectedImages((prev) => [...prev, ...newImages]);
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        }
      }
    } catch (e) {
      console.error('Image picker error:', e);
      setConfirmState({
        visible: true,
        title: t.chat.imageSelectionError,
        message: t.chat.imageSelectionErrorMessage,
        onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
      });
    }
  };

  const removeImage = (index: number) => {
    setSelectedImages((prev) => prev.filter((_, i) => i !== index));
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  };

  const handleModelPress = () => {
    if (onModelPress) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onModelPress();
      }, 0);
    }
  };

  const handleTokenPress = () => {
    if (onTokenPress) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onTokenPress();
      }, 0);
    }
  };

  return (
    <View
      style={[
        styles.outerContainer,
        { marginBottom: 12 + (useSafeAreaInsets().bottom || 0) }, // 🔑 适配安全区底部高度
        Platform.select({
          ios: {
            shadowColor: '#000',
            shadowOffset: { width: 0, height: 8 },
            shadowOpacity: 0.15,
            shadowRadius: 16,
          },
          android: {
            // elevation 需要背景色，但为了透出毛玻璃，使用极低透明度的背景
            backgroundColor: isDark ? 'rgba(0, 0, 0, 0.01)' : 'rgba(255, 255, 255, 0.01)',
            borderRadius: 24,
            elevation: 12,
            margin: 2,
          },
        }),
      ]}
    >
      {/* ✅ 编辑模式横条 Banner - 定位在输入框上边框中心 */}
      {editingMessageId && (
        <TouchableOpacity
          onPress={() => {
            setText('');
            onCancelEdit?.();
          }}
          activeOpacity={0.8}
          style={{
            position: 'absolute',
            top: -12, // 使文字横跨上边框内外
            left: 0,
            right: 0,
            zIndex: 100,
            alignItems: 'center', // 子元素水平居中
          }}
        >
          <View
            style={{
              backgroundColor: 'rgba(185, 28, 28, 0.95)',
              paddingHorizontal: 10,
              paddingVertical: 4,
              borderRadius: 10,
            }}
          >
            <Typography style={{ color: 'white', fontSize: 10, fontWeight: '600' }}>
              退出重发模式
            </Typography>
          </View>
        </TouchableOpacity>
      )}
      <BlurView
        intensity={isDark ? 80 : 120}
        tint={isDark ? 'dark' : 'light'}
        style={[
          styles.blurContainer,
          {
            // ✅ 编辑模式：红色边框
            borderColor: editingMessageId
              ? 'rgba(239, 68, 68, 0.7)'
              : isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(0, 0, 0, 0.08)',
            borderWidth: editingMessageId ? 2 : 1.5,
          },
          // ✅ 编辑模式：羽化红色阴影
          editingMessageId && {
            shadowColor: '#ef4444',
            shadowOffset: { width: 0, height: 0 },
            shadowOpacity: 0.5,
            shadowRadius: 12,
            elevation: 8,
          },
        ]}
      >
        <View
          style={[
            styles.overlay,
            {
              // 正常模式背景
              backgroundColor: isDark ? 'rgba(10, 10, 12, 0.8)' : 'rgba(255, 255, 255, 0.3)',
            },
          ]}
        />

        <View style={styles.topBar}>
          {currentModel && (
            <TouchableOpacity
              onPress={handleModelPress}
              activeOpacity={0.6}
              style={styles.modelBar}
            >
              <Cpu size={10} color={agentColor} />
              <Typography
                numberOfLines={1}
                ellipsizeMode="tail"
                className="text-[9px] font-black ml-1 uppercase tracking-tight text-gray-400 dark:text-gray-500"
                style={{ maxWidth: 120 }}
              >
                {currentModel}
              </Typography>
            </TouchableOpacity>
          )}

          {tokenUsage && (
            <TouchableOpacity
              onPress={handleTokenPress}
              activeOpacity={0.6}
              style={styles.tokenBar}
            >
              <Calculator size={10} color={isDark ? '#52525b' : '#a1a1aa'} />
              <Typography className="text-[9px] font-bold ml-1 text-gray-400 dark:text-zinc-600">
                {formatTokenCount(tokenUsage.total)} TOK
              </Typography>
            </TouchableOpacity>
          )}

          {/* Indicators Stack */}
          <View className="flex-col items-start gap-1">
            {/* SummaryIndicator 已移除，摘要状态由消息气泡内的 RAG 指示器统一处理 */}
          </View>

          {/* Tools Toggle */}
          <TouchableOpacity
            onPress={() => {
              const newState = !toolsEnabled;
              onToggleTools?.();
              // Use emitToast for global toast notification (Toast provides its own haptics)
              const { emitToast } = require('../../../lib/utils/toast-emitter');
              emitToast(newState ? '工具链已启用' : '工具链已隔离', newState ? 'success' : 'warning');
            }}
            activeOpacity={0.6}
            style={styles.modelBar}
          >
            {toolsEnabled ? (
              <Wrench size={10} color="#6366f1" />
            ) : (
              <Shield size={10} color={isDark ? '#52525b' : '#a1a1aa'} />
            )}
            <Typography
              className="text-[9px] font-black ml-1 uppercase tracking-tight"
              style={{ color: toolsEnabled ? '#6366f1' : (isDark ? '#52525b' : '#a1a1aa') }}
            >
              TOOLS
            </Typography>
          </TouchableOpacity>

          <View style={{ flex: 1 }} />

          {/* ✅ 执行模式切换器（替换原联网搜索和深度思考按钮） */}
          <View style={{ paddingRight: 12 }}>
            <ExecutionModeButton sessionId={sessionId} isDark={isDark} />
          </View>
        </View>

        <View style={styles.contentContainer}>
          <TouchableOpacity
            onPress={() => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                setShowAttachmentMenu(!showAttachmentMenu);
              }, 10);
            }}
            style={[styles.iconButton, { backgroundColor: isDark ? '#27272a' : '#f4f4f5' }]}
          >
            <Plus size={20} color="#64748b" />
          </TouchableOpacity>

          <View style={{ flex: 1 }}>
            {selectedImages.length > 0 && (
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                style={styles.previewContainer}
                contentContainerStyle={{ paddingHorizontal: 4 }}
              >
                {selectedImages.map((uri, index) => (
                  <View key={uri} style={styles.previewItem}>
                    <Image source={{ uri }} style={styles.previewImage} />
                    <TouchableOpacity
                      style={styles.removeButton}
                      onPress={() => removeImage(index)}
                    >
                      <X size={12} color="white" />
                    </TouchableOpacity>
                  </View>
                ))}
              </ScrollView>
            )}
            <View style={styles.inputWrapper}>
              {/* The line `onSendMessage={(content, options) => {` was removed as it was syntactically incorrect here. */}
              <TextInput
                style={[
                  styles.input,
                  { color: isDark ? '#fff' : '#000', backgroundColor: 'transparent' },
                ]}
                placeholder={
                  selectedImages.length > 0
                    ? 'Add a caption...'
                    : isInterventionMode
                      ? 'Steer the agent...'
                      : 'Message...'
                }
                placeholderTextColor="#94a3b8"
                underlineColorAndroid="transparent"
                multiline
                value={text}
                onChangeText={setText}
                editable={!disabled && !loading}
              />
            </View>
          </View>

          <View style={styles.buttonContainer}>
            {loading && (
              <Animated.View style={[styles.svgContainer, animatedCircleStyle]}>
                <Svg width="40" height="40" viewBox="0 0 40 40">
                  <Circle
                    cx="20"
                    cy="20"
                    r="18"
                    stroke={isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)'}
                    strokeWidth="2"
                    fill="none"
                  />
                  <Circle
                    cx="20"
                    cy="20"
                    r="18"
                    stroke={agentColor}
                    strokeWidth="2"
                    strokeDasharray="25, 100"
                    strokeLinecap="round"
                    fill="none"
                  />
                </Svg>
              </Animated.View>
            )}
            <TouchableOpacity
              onPress={handleSend}
              disabled={!loading && ((!text.trim() && selectedImages.length === 0) || disabled)}
              style={[
                styles.sendButton,
                {
                  backgroundColor:
                    text.trim() || selectedImages.length > 0 || loading
                      ? isInterventionMode
                        ? '#f59e0b' // Amber for intervention
                        : agentColor === '#6366f1'
                          ? colors[500]
                          : agentColor
                      : isDark
                        ? '#3f3f46'
                        : '#cbd5e1',
                  opacity: text.trim() || selectedImages.length > 0 || loading ? 1 : 0.4,
                },
              ]}
            >
              {loading ? (
                <Square size={14} color="white" fill="white" />
              ) : (
                <ArrowUp size={20} color="white" strokeWidth={2.5} />
              )}
            </TouchableOpacity>
          </View>
        </View>
      </BlurView>

      {/* Attachment Menu - Moved outside BlurView to prevent clipping due to overflow:hidden */}
      {showAttachmentMenu && (
        <View
          style={[
            styles.attachmentMenu,
            {
              backgroundColor: isDark ? 'rgba(39, 39, 42, 0.95)' : 'rgba(255, 255, 255, 0.95)',
              borderColor: isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)',
            },
          ]}
        >
          <TouchableOpacity
            style={styles.attachmentMenuItem}
            onPress={() => handlePickImage('camera')}
          >
            <Camera size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium text-gray-700 dark:text-gray-200">
              {t.chat.takePhoto}
            </Typography>
          </TouchableOpacity>
          <View style={{ height: 1, backgroundColor: isDark ? '#3f3f46' : '#e5e7eb' }} />
          <TouchableOpacity
            style={styles.attachmentMenuItem}
            onPress={() => handlePickImage('library')}
          >
            <ImageIcon size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium text-gray-700 dark:text-gray-200">
              {t.chat.selectFromGallery}
            </Typography>
          </TouchableOpacity>
        </View>
      )}

      <ConfirmDialog
        visible={confirmState.visible}
        title={confirmState.title}
        message={confirmState.message}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  outerContainer: {
    marginHorizontal: 20,
    marginBottom: 12,
    // 外部容器取消 iOS 阴影以防冲突，主要由内层处理
    ...Platform.select({
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 6 },
        shadowOpacity: 0.12,
        shadowRadius: 12,
      },
    }),
  },
  blurContainer: {
    borderRadius: 24,
    overflow: 'hidden',
    borderWidth: 1.5,
    // Android elevation
    ...Platform.select({
      android: {
        elevation: 10,
      },
    }),
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 12,
    paddingTop: 6,
    marginBottom: 2,
  },
  modelBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.03)',
    marginRight: 6,
  },
  tokenBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.02)',
  },
  toggleButton: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  contentContainer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  iconButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  inputWrapper: {
    // flex: 1, // Removed to allow parent to control layout
    minHeight: 36,
    justifyContent: 'center',
    paddingHorizontal: 8,
  },
  input: {
    fontSize: 16,
    maxHeight: 120,
    paddingVertical: 6,
  },
  previewContainer: {
    marginBottom: 8,
    height: 64,
  },
  previewItem: {
    width: 60,
    height: 60,
    marginRight: 8,
    borderRadius: 8,
    overflow: 'hidden',
    position: 'relative',
  },
  previewImage: {
    width: '100%',
    height: '100%',
    borderRadius: 8,
  },
  removeButton: {
    position: 'absolute',
    top: 2,
    right: 2,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 10,
    width: 18,
    height: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonContainer: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 2,
  },
  svgContainer: {
    position: 'absolute',
    top: 0,
    left: 0,
    width: 40,
    height: 40,
  },
  sendButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1,
  },
  attachmentMenu: {
    position: 'absolute',
    bottom: 60,
    left: 12,
    width: 160,
    borderRadius: 16,
    borderWidth: 1,
    overflow: 'hidden',
    // Shadow
    ...Platform.select({
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.15,
        shadowRadius: 12,
      },
      android: {
        elevation: 8,
      },
    }),
  },
  attachmentMenuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
});
