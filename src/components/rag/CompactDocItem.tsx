import React, { memo } from 'react';
import { View, TouchableOpacity, Image } from 'react-native';
import { Typography, ContextMenu } from '../ui';
import { TagCapsule } from './TagCapsule';
import {
  FileText,
  MoreVertical,
  CheckCircle,
  Loader,
  Circle,
  AlertCircle,
} from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from 'expo-haptics';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';

interface CompactDocItemProps {
  id: string;
  title: string;
  vectorized: 0 | 1 | 2 | -1; // 未处理|处理中|已完成|失败
  vectorCount: number;
  fileSize: number;
  onPress: () => void;
  onLongPress: () => void;
  onDelete: () => void;
  onVectorize?: () => void;
  onMove?: () => void;
  isSelected?: boolean;
  isSelectionMode?: boolean;
  tags?: Array<{ id: string; name: string; color: string }>;
  thumbnailPath?: string;
  onAssignTag?: () => void;
  onViewGraph?: () => void;
  onExtractGraph?: (strategy: 'full' | 'summary-first') => void;
}

export const CompactDocItem = memo<CompactDocItemProps>(
  ({
    id,
    title,
    vectorized,
    vectorCount,
    fileSize,
    onPress,
    onLongPress,
    onDelete,
    onVectorize,
    onMove,
    isSelected = false,
    isSelectionMode = false,

    tags,
    thumbnailPath,
    onAssignTag,
    onViewGraph,
    onExtractGraph,
  }) => {
    const { isDark, colors } = useTheme();

    const formatSize = (bytes: number) => {
      if (bytes < 1024) return `${bytes}B`;
      if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
      return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
    };

    const getStatusIcon = () => {
      if (isSelectionMode) {
        return isSelected ? (
          <CheckCircle size={20} color={colors[500]} fill={isDark ? colors.opacity20 : colors.opacity10} />
        ) : (
          <Circle size={20} color="#94a3b8" />
        );
      }

      switch (vectorized) {
        case 2: // 已完成
          return <CheckCircle size={18} color="#10b981" />;
        case 1: // 处理中
          return <Loader size={18} color="#eab308" className="animate-spin" />;
        case -1: // 失败
          return <AlertCircle size={18} color="#ef4444" />;
        default: // 未处理
          return <Circle size={18} color="#94a3b8" />;
      }
    };

    const getStatusText = () => {
      switch (vectorized) {
        case 2:
          return `${vectorCount} chunks`;
        case 1:
          return '处理中...';
        case -1:
          return '失败';
        default:
          return '未处理';
      }
    };

    const menuItems = [
      vectorized === 0 || vectorized === -1
        ? {
          key: 'vectorize',
          label: '向量化',
          onPress: () => {
            setTimeout(() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
              onVectorize?.();
            }, 10);
          },
        }
        : null,
      vectorized === 2
        ? {
          key: 'view-graph',
          label: '查看知识图谱',
          onPress: () => {
            setTimeout(() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
              onViewGraph?.();
            }, 10);
          },
        }
        : null,
      {
        key: 'move',
        label: '移动到文件夹',
        onPress: () => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            onMove?.();
          }, 10);
        },
      },
      {
        key: 'tag',
        label: '添加标签',
        onPress: () => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onAssignTag?.();
          }, 10);
        },
      },
      // 知识图谱提取
      {
        key: 'extract-full',
        label: '提取知识图谱 (全量)',
        onPress: () => {
          setTimeout(() => {
            onExtractGraph?.('full');
          }, 10);
        },
      },
      {
        key: 'extract-summary',
        label: '提取知识图谱 (摘要)',
        onPress: () => {
          setTimeout(() => {
            onExtractGraph?.('summary-first');
          }, 10);
        },
      },
      {
        key: 'delete',
        label: '删除',
        onPress: () => {
          setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
            onDelete();
          }, 10);
        },
        destructive: true,
      },
    ].filter(Boolean);

    return (
      <Animated.View
        entering={FadeIn.duration(200)}
        exiting={FadeOut.duration(150)}
        className="mx-6 mb-1.5 flex-row items-center bg-gray-50 dark:bg-zinc-900/50 rounded-xl border border-indigo-50 dark:border-indigo-500/10 pr-1"
        style={isSelected ? { borderColor: colors[500], backgroundColor: isDark ? colors.opacity20 : colors.opacity10 } : undefined}
      >
        <TouchableOpacity
          onPress={onPress}
          onLongPress={onLongPress}
          activeOpacity={0.7}
          className="flex-1 flex-row items-center px-3 py-2.5"
        >
          {/* 文档图标 (Document Icon/Thumbnail) */}
          <View className="w-8 h-8 rounded-lg bg-white dark:bg-zinc-800 items-center justify-center mr-3 overflow-hidden">
            {thumbnailPath ? (
              <Image source={{ uri: thumbnailPath }} className="w-full h-full" resizeMode="cover" />
            ) : (
              <FileText size={16} color={colors[500]} strokeWidth={2} />
            )}
          </View>

          {/* 文档信息 */}
          <View className="flex-1 min-w-0">
            <Typography
              className="font-bold text-sm text-gray-900 dark:text-white"
              numberOfLines={1}
            >
              {title}
            </Typography>
            <Typography className="text-xs text-gray-400 mt-0.5">
              {formatSize(fileSize)} · {getStatusText()}
            </Typography>

            {/* Tags */}
            {tags && tags.length > 0 && (
              <View className="flex-row items-center mt-1.5 flex-wrap">
                {tags.map((tag) => (
                  <TagCapsule key={tag.id} name={tag.name} color={tag.color} />
                ))}
              </View>
            )}
          </View>

          {/* 状态指示器 / Checkbox */}
          <View className="w-6 h-6 items-center justify-center mr-2">{getStatusIcon()}</View>
        </TouchableOpacity>

        {/* 操作菜单 - 独立触摸区域 */}
        {!isSelectionMode && (
          <ContextMenu items={menuItems as any} triggerOnPress>
            <View className="p-3">
              <MoreVertical size={16} color="#94a3b8" />
            </View>
          </ContextMenu>
        )}
      </Animated.View>
    );
  },
);

CompactDocItem.displayName = 'CompactDocItem';
