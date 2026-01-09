import React, { useEffect } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, ContextMenu } from '../ui';
import { Folder, FolderOpen, ChevronRight, MoreVertical } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from 'expo-haptics';
import Animated, {
  useAnimatedStyle,
  withSpring,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import type { ContextMenuItem } from '../ui/ContextMenu';

interface FolderItemProps {
  id: string;
  name: string;
  childCount: number;
  isExpanded: boolean;
  level?: number;
  onToggle: () => void;
  onPress: () => void;
  onLongPress?: () => void;
  onDelete?: () => void;
  onRename?: () => void;
  onMove?: () => void;
  onViewGraph?: () => void;
  onExtractGraph?: (strategy: 'full' | 'summary-first') => void;
}

export const FolderItem: React.FC<FolderItemProps> = ({
  id,
  name,
  childCount,
  isExpanded,
  level = 0,
  onToggle,
  onPress,
  onLongPress,
  onDelete,
  onRename,
  onMove,
  onViewGraph,
  onExtractGraph,
}) => {
  const { isDark } = useTheme();

  const handleToggle = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      onToggle();
    }, 10);
  };

  const handlePress = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      onPress();
    }, 10);
  };

  const chevronRotation = useSharedValue(isExpanded ? 90 : 0);
  useEffect(() => {
    chevronRotation.value = withSpring(isExpanded ? 90 : 0, {
      damping: 15,
      stiffness: 200,
    });
  }, [isExpanded]);

  const chevronStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${chevronRotation.value}deg` }],
  }));

  const menuItems: ContextMenuItem[] = [
    {
      label: '查看知识图谱',
      onPress: () => {
        setTimeout(() => {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
          onViewGraph?.();
        }, 10);
      },
    },
    {
      label: '生成全量图谱 (批量)',
      onPress: () => onExtractGraph?.('full'),
    },
    {
      label: '生成摘要图谱 (批量)',
      onPress: () => onExtractGraph?.('summary-first'),
    },
    {
      label: '重命名',
      onPress: () => onRename?.(),
    },
    {
      label: '移动到文件夹',
      onPress: () => onMove?.(),
    },
    {
      label: '删除文件夹',
      destructive: true,
      onPress: () => onDelete?.(),
    },
  ];

  return (
    <View className="mx-6 mb-1.5 flex-row items-center bg-gray-50 dark:bg-zinc-900/50 rounded-xl border border-gray-100 dark:border-zinc-800 pr-1">
      <TouchableOpacity
        onPress={handlePress}
        onLongPress={onLongPress}
        activeOpacity={0.7}
        className="flex-1 flex-row items-center px-3 py-2.5"
      >
        {/* 文件夹图标 */}
        <View className="w-8 h-8 rounded-lg bg-amber-50 dark:bg-amber-900/20 items-center justify-center mr-3">
          {isExpanded ? (
            <FolderOpen size={16} color="#f59e0b" strokeWidth={2} />
          ) : (
            <Folder size={16} color="#f59e0b" strokeWidth={2} />
          )}
        </View>

        {/* 文件夹信息 */}
        <View className="flex-1">
          <Typography
            className="font-bold text-sm text-gray-900 dark:text-white"
            numberOfLines={1}
          >
            {name}
          </Typography>
          <Typography className="text-xs text-gray-400 mt-0.5">{childCount} 个项目</Typography>
        </View>
      </TouchableOpacity>

      {/* 操作图标 - 独立触摸区域 */}
      <ContextMenu items={menuItems} triggerOnPress>
        <View className="p-3">
          <MoreVertical size={16} color="#94a3b8" />
        </View>
      </ContextMenu>
    </View>
  );
};
