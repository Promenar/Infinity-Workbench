import React from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography } from '../ui';
import { Folder, MoreVertical, Check } from 'lucide-react-native';
import Animated, { ZoomIn, ZoomOut } from 'react-native-reanimated';
import { clsx } from 'clsx';

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
  return (
    <TouchableOpacity
      activeOpacity={0.7}
      onPress={onPress}
      onLongPress={onLongPress}
      delayLongPress={200}
      className={clsx(
        'p-4 rounded-[28px] flex-1 h-36 justify-between border relative overflow-hidden',
        isSelected
          ? 'bg-indigo-50/80 dark:bg-indigo-900/40 border-indigo-200 dark:border-indigo-800'
          : 'bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800',
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
          <Folder size={24} color={isSelected ? '#6366f1' : iconColor} strokeWidth={1.5} />
        </View>

        {isSelectionMode ? (
          <Animated.View
            entering={ZoomIn.duration(300)}
            exiting={ZoomOut.duration(200)}
            className={clsx(
              'w-6 h-6 rounded-full items-center justify-center border',
              isSelected
                ? 'bg-indigo-500 border-indigo-500'
                : 'bg-white dark:bg-zinc-800 border-gray-200 dark:border-zinc-700',
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
          className={clsx(
            'font-black text-[19px] mb-0.5',
            isSelected ? 'text-indigo-900 dark:text-indigo-100' : 'text-gray-900 dark:text-white',
          )}
        >
          {label}
        </Typography>
        <Typography
          variant="caption"
          className={clsx(
            'font-bold uppercase text-[10px] tracking-widest',
            isSelected ? 'text-indigo-400' : 'text-gray-400',
          )}
        >
          {count}
        </Typography>
      </View>
    </TouchableOpacity>
  );
};
