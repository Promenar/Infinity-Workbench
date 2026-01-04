import React from 'react';
import { View, TouchableOpacity, ActivityIndicator } from 'react-native';
import { Typography } from '../ui';
import { FolderPlus, Network } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from 'expo-haptics';

interface ControlBarProps {
  onNewFolder: () => void;
  onViewGraph: () => void;
  currentTask?: {
    docTitle: string;
    progress: number;
  } | null;
  queueLength: number;
}

export const ControlBar: React.FC<ControlBarProps> = ({
  onNewFolder,
  onViewGraph,
  currentTask,
  queueLength,
}) => {
  const { isDark } = useTheme();

  const handlePress = (action: () => void) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      action();
    }, 10);
  };

  return (
    <View
      className="mx-6 mb-4 bg-white dark:bg-zinc-900 
                    rounded-2xl border border-gray-100 dark:border-zinc-800 p-3"
    >
      <View className="flex-row items-center justify-between">
        {/* 操作按钮 */}
        <View className="flex-row gap-2">
          <TouchableOpacity
            onPress={() => handlePress(onNewFolder)}
            className="h-10 px-4 bg-indigo-50 dark:bg-indigo-900/30 
                       rounded-xl flex-row items-center gap-2 active:opacity-70"
          >
            <FolderPlus size={16} color="#6366f1" strokeWidth={2.5} />
            <Typography className="text-indigo-600 dark:text-indigo-400 font-bold text-sm">
              新建
            </Typography>
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => handlePress(onViewGraph)}
            className="h-10 px-4 bg-violet-50 dark:bg-violet-900/30 
                       rounded-xl flex-row items-center gap-2 active:opacity-70"
          >
            <Network size={16} color="#8b5cf6" strokeWidth={2.5} />
            <Typography className="text-violet-600 dark:text-violet-400 font-bold text-sm">
              知识图谱
            </Typography>
          </TouchableOpacity>
        </View>

        {/* 任务状态 */}
        {currentTask && (
          <View className="flex-row items-center gap-2">
            <ActivityIndicator size="small" color="#6366f1" />
            <View>
              <Typography className="text-xs font-bold text-gray-900 dark:text-white">
                {currentTask.progress}%
              </Typography>
              <Typography className="text-[10px] text-gray-400">{queueLength} 待处理</Typography>
            </View>
          </View>
        )}
      </View>
    </View>
  );
};
