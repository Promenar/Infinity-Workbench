import React from 'react';
import { View, TouchableOpacity, ActivityIndicator } from 'react-native';
import { Typography } from '../ui';
import { FolderPlus, Network } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';

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
  const { isDark, colors } = useTheme();
  const { t } = useI18n();

  const handlePress = (action: () => void) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      action();
    }, 10);
  };

  return (
    <View
      style={{
        backgroundColor: isDark ? 'rgba(26, 28, 46, 0.4)' : 'rgba(255, 255, 255, 0.9)',
        borderColor: isDark ? 'rgba(99, 102, 241, 0.15)' : 'rgba(0, 0, 0, 0.05)',
      }}
      className="mx-6 mb-0.5 rounded-2xl border p-3"
    >
      <View className="flex-row items-center gap-2">
        {/* 操作按钮 - 使用 flex-1 平分空间 */}
        <TouchableOpacity
          onPress={() => handlePress(onNewFolder)}
          style={{ backgroundColor: isDark ? 'rgba(99, 102, 241, 0.1)' : colors.opacity10 }}
          className="flex-1 h-10 px-2 rounded-xl flex-row items-center justify-center gap-2 border border-transparent active:opacity-70"
        >
          <FolderPlus size={18} color={colors[500]} strokeWidth={2.5} />
          <Typography style={{ color: colors[500] }} className="font-bold text-sm" numberOfLines={1}>
            {t.library.new}
          </Typography>
        </TouchableOpacity>

        <TouchableOpacity
          onPress={() => handlePress(onViewGraph)}
          className="flex-1 h-10 px-2 bg-violet-50 dark:bg-violet-900/30 
                       rounded-xl flex-row items-center justify-center gap-2 active:opacity-70"
        >
          <Network size={18} color="#8b5cf6" strokeWidth={2.5} />
          <Typography className="text-violet-600 dark:text-violet-400 font-bold text-sm" numberOfLines={1}>
            {t.rag.knowledgeGraph}
          </Typography>
        </TouchableOpacity>

        {/* 任务状态 - 自适应宽度 */}
        {currentTask && (
          <View className="flex-row items-center gap-2 pl-1 border-l border-indigo-50 dark:border-indigo-500/10">
            <ActivityIndicator size="small" color={colors[500]} />
            <View>
              <Typography className="text-xs font-bold text-gray-900 dark:text-white">
                {Math.round(currentTask.progress)}%
              </Typography>
              <Typography className="text-[10px] text-gray-400">
                {t.library.pending.replace('{count}', queueLength.toString())}
              </Typography>
            </View>
          </View>
        )}
      </View>
    </View>
  );
};
