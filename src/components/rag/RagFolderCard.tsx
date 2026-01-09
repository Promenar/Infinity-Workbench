import React from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography } from '../ui';
import { Folder, MoreVertical, Check } from 'lucide-react-native';
import Animated, { ZoomIn, ZoomOut } from 'react-native-reanimated';
import { clsx } from 'clsx';
import { useTheme } from '../../theme/ThemeProvider';

export const RagFolderCard = ({
  label,
  count,
  iconColor,
  isSelected,
  isSelectionMode,
  onPress,
  onLongPress,
}: {
  label: string;
  count: string;
  iconColor: string;
  isSelected: boolean;
  isSelectionMode: boolean;
  onPress: () => void;
  onLongPress: () => void;
}) => {
  const { isDark, colors } = useTheme();

  return (
    <TouchableOpacity
      activeOpacity={0.7}
      onPress={onPress}
      onLongPress={onLongPress}
      delayLongPress={200}
      style={isSelected ? { backgroundColor: isDark ? colors.opacity20 : colors.opacity10, borderColor: colors.opacity30 } : {}}
      className={clsx(
        'p-4 rounded-2xl border mb-3',
        !isSelected && 'bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800',
      )}
    >
      <View className="flex-row justify-between items-start">
        <View
          className={clsx(
            'w-12 h-12 rounded-2xl items-center justify-center border',
            isSelected
              ? 'bg-white dark:bg-indigo-900 border-indigo-100'
              : 'bg-white dark:bg-zinc-800 border-gray-100 dark:border-zinc-700',
          )}
        >
          <Folder size={24} color={isSelected ? colors[500] : iconColor} strokeWidth={1.5} />
        </View>

        {isSelectionMode ? (
          <Animated.View
            entering={ZoomIn.duration(300)}
            exiting={ZoomOut.duration(200)}
            style={isSelected ? { backgroundColor: colors[500], borderColor: colors[500] } : {}}
            className={clsx(
              'w-5 h-5 rounded-full border-2 items-center justify-center mr-3',
              !isSelected && 'border-gray-300 dark:border-zinc-700',
            )}
          >
            {isSelected && <Check size={14} color="white" strokeWidth={3} />}
          </Animated.View>
        ) : (
          <View className="p-1">
            <MoreVertical size={18} color="#94a3b8" />
          </View>
        )}
      </View>

      <View>
        <Typography
          variant="h3"
          style={isSelected ? { color: isDark ? colors[100] : colors[900] } : {}}
          className={clsx(
            'font-bold text-base',
            !isSelected && 'text-gray-900 dark:text-white',
          )}
        >
          {label}
        </Typography>
        <Typography
          variant="caption"
          style={isSelected ? { color: colors[400] } : {}}
          className={clsx(
            'text-[11px] font-medium mt-0.5',
            !isSelected && 'text-gray-400',
          )}
        >
          {count}
        </Typography>
      </View>
    </TouchableOpacity>
  );
};
