import React, { useRef, useCallback } from 'react';
import { useMessageContext, MessageProvider } from './MessageContext';
import { ContextMenu, ContextMenuRef } from '../../../../components/ui';
import { Copy, Type, Share2, RefreshCw, BrainCircuit, FileInput, FileText, Trash2 } from 'lucide-react-native';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from '../../../../lib/haptics';

export const MessageContextMenu: React.FC<{ children: React.ReactNode }> = React.memo(({ children }) => {
  const context = useMessageContext();
  const {
    message,
    isUser,
    isLastAssistantMessage,
    onRegenerate,
    onResend,
    onDelete,
    onExtractGraph,
    onVectorize,
    onSummarize,
    onShare,
    onSelectText
  } = context as any;

  const menuRef = useRef<ContextMenuRef>(null);

  const handleOpenMenu = useCallback((event: any) => {
    menuRef.current?.open(event);
  }, []);

  const items = [
    {
      label: '复制内容',
      icon: <Copy size={18} />,
      onPress: () => {
        Clipboard.setStringAsync(message.content);
        setTimeout(() => {
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        }, 10);
      },
    },
    {
      label: '选择文本',
      icon: <Type size={18} />,
      onPress: onSelectText,
    },
    {
      label: '分享消息',
      icon: <Share2 size={18} />,
      onPress: onShare,
    },
    // AI specific
    !isUser && isLastAssistantMessage && onRegenerate && {
      label: '重新生成',
      icon: <RefreshCw size={18} />,
      onPress: () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onRegenerate();
      },
    },
    // User specific
    isUser && onResend && {
      label: '重新发送',
      icon: <RefreshCw size={18} />,
      onPress: () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onResend();
      },
    },
    {
      label: '知识图谱',
      icon: <BrainCircuit size={18} />,
      onPress: onExtractGraph,
    },
    {
      label: '手动向量',
      icon: <FileInput size={18} />,
      onPress: onVectorize,
    },
    {
      label: '触发摘要',
      icon: <FileText size={18} />,
      onPress: onSummarize,
    },
    {
      label: '删除消息',
      icon: <Trash2 size={18} />,
      destructive: true,
      onPress: () => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
        onDelete?.();
      },
    },
  ].filter(Boolean) as any[];

  return (
    <ContextMenu ref={menuRef} items={items} disablePressTrigger={true}>
      <MessageProvider value={{ ...context, onOpenMenu: handleOpenMenu }}>
        {children}
      </MessageProvider>
    </ContextMenu>
  );
});
