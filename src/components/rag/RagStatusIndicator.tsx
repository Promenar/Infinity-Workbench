import React, { useEffect, useState } from 'react';
import { View, TouchableOpacity } from 'react-native';
import Animated, { FadeInDown, FadeOutDown, Layout } from 'react-native-reanimated';
import { useRagStore } from '../../store/rag-store';
import { Typography } from '../ui/Typography';
import {
  Database,
  FileText,
  Loader2,
  Save,
  Scissors,
  CheckCircle2,
  AlertCircle,
  X,
} from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';

export function RagStatusIndicator() {
  const { currentTask, vectorizationQueue } = useRagStore();
  const { isDark, colors } = useTheme();
  const [visible, setVisible] = useState(false);

  // 延迟隐藏逻辑
  useEffect(() => {
    if (currentTask || vectorizationQueue.length > 0) {
      setVisible(true);
    } else {
      const timer = setTimeout(() => {
        setVisible(false);
      }, 2000); // 完成后停留2秒
      return () => clearTimeout(timer);
    }
  }, [currentTask, vectorizationQueue.length]);

  if (!visible && !currentTask) return null;

  // 状态映射
  const getStatusConfig = () => {
    if (!currentTask) {
      return {
        icon: <CheckCircle2 size={14} color="#10b981" />,
        text: '全部完成',
        color: 'bg-emerald-50 dark:bg-emerald-900/20',
      };
    }

    switch (currentTask.status) {
      case 'pending':
        return {
          icon: <Loader2 size={14} color={colors[500]} className="animate-spin" />,
          text: '等待中...',
          color: isDark ? 'bg-indigo-900/20' : colors.opacity10,
        };
      case 'reader':
      case 'chunking':
        return {
          icon: <Scissors size={14} color="#f59e0b" />,
          text: '正在切分文档...',
          color: 'bg-amber-50 dark:bg-amber-900/20',
        };
      case 'vectorizing':
        return {
          icon: <Database size={14} color="#3b82f6" />,
          text: `向量化中 (${currentTask.progress}%)`,
          color: 'bg-blue-50 dark:bg-blue-900/20',
        };
      case 'saving':
        return {
          icon: <Save size={14} color="#8b5cf6" />,
          text: '正在存储...',
          color: 'bg-purple-50 dark:bg-purple-900/20',
        };
      case 'completed':
        return {
          icon: <CheckCircle2 size={14} color="#10b981" />,
          text: '处理完成',
          color: 'bg-emerald-50 dark:bg-emerald-900/20',
        };
      case 'failed':
        return {
          icon: <AlertCircle size={14} color="#ef4444" />,
          text: '处理失败',
          color: 'bg-red-50 dark:bg-red-900/20',
        };
      default:
        return {
          icon: <FileText size={14} color="#64748b" />,
          text: '处理中...',
          color: 'bg-gray-50 dark:bg-zinc-800',
        };
    }
  };

  const config = getStatusConfig();
  const queueCount = vectorizationQueue.length;

  return (
    <Animated.View
      entering={FadeInDown.springify()}
      exiting={FadeOutDown}
      layout={Layout.springify()}
      style={{
        position: 'absolute',
        bottom: 80, // 上方 TabBar
        left: 20,
        right: 20,
        alignItems: 'center',
        zIndex: 100,
        pointerEvents: 'box-none',
      }}
    >
      <TouchableOpacity
        activeOpacity={0.9}
        className={`flex-row items-center px-4 py-2.5 rounded-full shadow-lg border border-black/5 dark:border-white/10 ${isDark ? 'bg-zinc-900' : 'bg-white'}`}
      >
        {/* 状态图标容器 */}
        <View className={`w-6 h-6 rounded-full items-center justify-center mr-3 ${config.color}`}>
          {config.icon}
        </View>

        <View className="flex-1 mr-4">
          <Typography className="text-xs font-bold text-gray-900 dark:text-white" numberOfLines={1}>
            {currentTask ? currentTask.docTitle : '所有任务已完成'}
          </Typography>
          <Typography className="text-[10px] text-gray-500 font-medium">{config.text}</Typography>
        </View>

        {/* 队列计数 & 取消按钮 */}
        <View className="flex-row items-center gap-2">
          {queueCount > 0 && (
            <TouchableOpacity
              onPress={() => {
                const { clearVectorizationQueue } = useRagStore.getState();
                clearVectorizationQueue();
              }}
              className="w-6 h-6 rounded-full bg-gray-100 dark:bg-zinc-800 items-center justify-center border border-gray-200 dark:border-zinc-700"
            >
              <X size={12} color="#64748b" />
            </TouchableOpacity>
          )}

          {queueCount > 1 && (
            <View className="bg-gray-100 dark:bg-zinc-800 px-2 py-0.5 rounded-md">
              <Typography className="text-[10px] font-bold text-gray-500">
                +{queueCount - 1}
              </Typography>
            </View>
          )}
        </View>
      </TouchableOpacity>
    </Animated.View>
  );
}
