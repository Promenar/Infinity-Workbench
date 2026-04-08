import React, { useEffect, useMemo, useState } from 'react';
import {
  View,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Platform,
  Image,
  ScrollView,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  ArrowUp,
  Plus,
  Square,
  X,
  Image as ImageIcon,
  Camera,
  FileText,
} from 'lucide-react-native';
import { BlurView } from 'expo-blur';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
  cancelAnimation,
} from 'react-native-reanimated';
import Svg, { Circle } from 'react-native-svg';

import * as Haptics from '../../../lib/haptics';
import { useI18n } from '../../../lib/i18n';
import { useTheme } from '../../../theme/ThemeProvider';
import { Typography, ConfirmDialog } from '../../../components/ui';
import { Glass } from '../../../theme/glass';
import { ANIMATION_DURATION } from '../../../theme/animations';
import { useChatStore } from '../../../store/chat-store';
import { documentProcessor } from '../../../lib/rag/document-processor';
import { useMediaPicker } from './input/hooks/useMediaPicker';
import { useChatInputState } from './input/hooks/useChatInputState';
import { useKeyboardTracking } from './input/hooks/useKeyboardTracking';
import { getInputStyles } from './input/styles/input-styles';

interface ChatInputProps {
  onSendMessage: (
    text: string,
    options?: { webSearch?: boolean; reasoning?: boolean; images?: string[]; files?: any[] },
  ) => void;
  onStop?: () => void;
  sessionId: string;
  disabled?: boolean;
  loading?: boolean;
  agentColor?: string;
  /** 顶部工具栏组件（通过组合模式传入，保持ChatInput轻量） */
  topBar?: React.ReactNode;
  isInterventionMode?: boolean;
  editingMessageId?: string;
  initialEditText?: string;
  onCancelEdit?: () => void;
}

export function ChatInput({
  onSendMessage,
  onStop,
  sessionId,
  disabled,
  loading,
  agentColor = '#6366f1',
  topBar,
  isInterventionMode,
  editingMessageId,
  initialEditText,
  onCancelEdit,
}: ChatInputProps) {
  const { t } = useI18n();
  const { isDark, colors } = useTheme();
  const insets = useSafeAreaInsets();
  const styles = useMemo(() => getInputStyles(isDark, colors), [isDark, colors]);

  const rotation = useSharedValue(0);
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);
  const pdfExtractorRef = React.useRef<any>(null);

  // 1. Session & Options
  const session = useChatStore((state) => state.getSession(sessionId));
  const updateSessionDraft = useChatStore((state) => state.updateSessionDraft);
  const webSearchEnabled = session?.options?.webSearch ?? false;
  const reasoningEnabled = session?.options?.reasoning ?? true;

  // 2. Logic Extraction Hooks
  const media = useMediaPicker(t);

  const {
    text,
    setText,
    handleSend,
  } = useChatInputState({
    sessionId,
    onSendMessage,
    clearAttachments: media.clearAttachments,
    webSearchEnabled,
    reasoningEnabled,
    selectedImages: media.selectedImages,
    selectedFiles: media.selectedFiles,
    editingMessageId,
    initialEditText,
    onStop,
  });

  const {
    isFocused,
    focusAnimatedStyle,
    handleFocus,
    handleBlur,
  } = useKeyboardTracking({ isDark, agentColor, colors });

  // 3. Side Effects
  useEffect(() => {
    const timer = setTimeout(() => {
      if (pdfExtractorRef.current) {
        documentProcessor.setPdfExtractor(pdfExtractorRef.current);
      }
    }, 500);
    return () => clearTimeout(timer);
  }, []);

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

  const animatedCircleStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
  }));

  const placeholderText = useMemo(() => {
    if (session?.loopStatus === 'paused' && !!session?.pendingIntervention) return "请回复以继续任务...";
    return "发送消息...";
  }, [session?.loopStatus, session?.pendingIntervention]);

  // Actions
  const toggleAttachmentMenu = () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    setShowAttachmentMenu(!showAttachmentMenu);
  };

  return (
    <View style={[styles.outerContainer, { marginBottom: 12 + insets.bottom }]}>
      {/* Edit Mode Banner */}
      {editingMessageId && (
        <TouchableOpacity
          onPress={() => {
            setText('');
            onCancelEdit?.();
          }}
          activeOpacity={0.8}
          style={styles.editBanner}
        >
          <View style={styles.editBadge}>
            <Typography style={styles.editBadgeText}>退出重发模式</Typography>
          </View>
        </TouchableOpacity>
      )}

      <BlurView
        intensity={Glass.Header.intensity}
        tint={isDark ? Glass.Header.tint.dark : Glass.Header.tint.light}
        experimentalBlurMethod='dimezisBlurView'
        style={[
          styles.blurContainer,
          {
            borderColor: editingMessageId
              ? 'rgba(239, 68, 68, 0.7)'
              : isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(0, 0, 0, 0.08)',
            borderWidth: editingMessageId ? 2 : 1.5,
          },
          editingMessageId && styles.editShadow,
        ]}
      >
        <View style={[styles.overlay, { backgroundColor: isDark ? `rgba(0,0,0,${Glass.Header.opacity.dark})` : `rgba(255,255,255,${Glass.Header.opacity.light})` }]} />

        {/* Top Bar: 由外部通过props传入，保持ChatInput轻量 */}
        {topBar}

        {/* Content Area: Attachments, Input, Send */}
        <View style={styles.contentContainer}>
          <TouchableOpacity onPress={toggleAttachmentMenu} style={[styles.iconButton, { backgroundColor: isDark ? '#27272a' : '#f4f4f5' }]}>
            <Plus size={20} color="#64748b" />
          </TouchableOpacity>

          <View style={{ flex: 1 }}>
            {/* Image Preview */}
            {media.selectedImages.length > 0 && (
              <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.previewContainer}>
                {media.selectedImages.map((uri, index) => (
                  <View key={uri} style={styles.previewItem}>
                    <Image source={{ uri }} style={styles.previewImage} />
                    <TouchableOpacity style={styles.removeButton} onPress={() => media.removeImage(index)}>
                      <X size={12} color="white" />
                    </TouchableOpacity>
                  </View>
                ))}
              </ScrollView>
            )}

            {/* File Preview */}
            {media.selectedFiles.length > 0 && (
              <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.previewContainer}>
                {media.selectedFiles.map((file, index) => (
                  <View key={file.id} style={[styles.previewItem, styles.filePreviewItem, { backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }]}>
                    <FileText size={24} color={isDark ? '#e4e4e7' : '#4b5563'} />
                    <Typography numberOfLines={1} style={styles.filePreviewText}>{file.name}</Typography>
                    <TouchableOpacity style={styles.removeButton} onPress={() => media.removeFile(index)}>
                      <X size={12} color="white" />
                    </TouchableOpacity>
                  </View>
                ))}
              </ScrollView>
            )}

            <Animated.View style={[styles.inputWrapper, focusAnimatedStyle, { shadowColor: agentColor || colors[500] }]}>
              <TextInput
                style={[styles.input, { color: isDark ? '#fff' : '#000' }]}
                placeholder={
                  media.selectedImages.length > 0 || media.selectedFiles.length > 0
                    ? 'Add a caption...'
                    : isInterventionMode ? 'Steer the agent...' : placeholderText
                }
                placeholderTextColor="#94a3b8"
                underlineColorAndroid="transparent"
                multiline
                value={text}
                onChangeText={setText}
                editable={!disabled && !loading}
                onFocus={handleFocus}
                onBlur={handleBlur}
                scrollEnabled
              />
            </Animated.View>
          </View>

          <View style={styles.buttonContainer}>
            {loading && (
              <Animated.View style={[styles.svgContainer, animatedCircleStyle]}>
                <Svg width="40" height="40" viewBox="0 0 40 40">
                  <Circle cx="20" cy="20" r="18" stroke={isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)'} strokeWidth="2" fill="none" />
                  <Circle cx="20" cy="20" r="18" stroke={agentColor} strokeWidth="2" strokeDasharray="25, 100" strokeLinecap="round" fill="none" />
                </Svg>
              </Animated.View>
            )}
            <TouchableOpacity
              onPress={() => handleSend(!!loading, !!disabled)}
              disabled={!loading && ((!text.trim() && media.selectedImages.length === 0) || disabled)}
              style={[
                styles.sendButton,
                {
                  backgroundColor: (text.trim() || media.selectedImages.length > 0 || media.selectedFiles.length > 0 || loading)
                    ? isInterventionMode ? '#f59e0b' : agentColor
                    : isDark ? '#3f3f46' : '#cbd5e1',
                  opacity: (text.trim() || media.selectedImages.length > 0 || media.selectedFiles.length > 0 || loading) ? 1 : 0.4,
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

      {/* Attachment Menu */}
      {showAttachmentMenu && (
        <View style={[styles.attachmentMenu, { backgroundColor: isDark ? 'rgba(39, 39, 42, 0.95)' : 'rgba(255, 255, 255, 0.95)', borderColor: isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)' }]}>
          <TouchableOpacity style={styles.attachmentMenuItem} onPress={() => { setShowAttachmentMenu(false); media.handlePickImage('camera'); }}>
            <Camera size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium">{t.chat.takePhoto}</Typography>
          </TouchableOpacity>
          <View style={styles.divider} />
          <TouchableOpacity style={styles.attachmentMenuItem} onPress={() => { setShowAttachmentMenu(false); media.handlePickImage('library'); }}>
            <ImageIcon size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium">{t.chat.selectFromGallery}</Typography>
          </TouchableOpacity>
          <View style={styles.divider} />
          <TouchableOpacity style={styles.attachmentMenuItem} onPress={() => { setShowAttachmentMenu(false); media.handlePickFile(); }}>
            <FileText size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium">{t.common?.document || 'Document'}</Typography>
          </TouchableOpacity>
        </View>
      )}

      <ConfirmDialog
        visible={media.confirmState.visible}
        title={media.confirmState.title}
        message={media.confirmState.message}
        onConfirm={media.confirmState.onConfirm}
        onCancel={() => media.setConfirmState((prev: any) => ({ ...prev, visible: false }))}
      />
    </View>
  );
}
