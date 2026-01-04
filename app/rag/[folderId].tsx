import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { View, TouchableOpacity, BackHandler } from 'react-native';
import { PageLayout, Typography, useToast, GlassHeader } from '../../src/components/ui';
import { ChevronLeft, Plus, X, Search, FolderInput } from 'lucide-react-native';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import * as Haptics from '../../src/lib/haptics';
import { MOCK_DOCS, MOCK_FOLDERS, DocItem } from '../../src/data/mock';
import Animated, {
  FadeIn,
  FadeOut,
  withTiming,
  Easing,
  SharedValue,
  useSharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RagDocItem } from '../../src/components/rag/RagDocItem';
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';

export default function FolderDetailScreen() {
  const { folderId } = useLocalSearchParams<{ folderId: string }>();
  const router = useRouter();
  const { showToast } = useToast();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [isSelectionMode, setIsSelectionMode] = useState(false);
  const { t } = useI18n();

  // 找出当前文件夹信息
  const folderInfo = useMemo(
    () => MOCK_FOLDERS.find((f) => f.id === folderId) || { label: 'Unknown', iconColor: '#94a3b8' },
    [folderId],
  );

  // 过滤该文件夹下的文件
  const folderDocs = useMemo(
    () => MOCK_DOCS.filter((doc) => doc.folderId === folderId),
    [folderId],
  );

  const translatedLabel = useMemo(() => {
    if (folderId === 'folder_work') return t.library.work;
    if (folderId === 'folder_private') return t.library.private;
    return folderInfo.label;
  }, [folderId, t]);

  const selectionProgress: SharedValue<number> = useSharedValue(0);

  useEffect(() => {
    selectionProgress.value = withTiming(isSelectionMode ? 1 : 0, {
      duration: 300,
      easing: Easing.bezier(0.25, 0.1, 0.25, 1),
    });
  }, [isSelectionMode]);

  const toggleSelection = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else {
        next.add(id);
        Haptics.selectionAsync();
      }
      if (next.size === 0) setIsSelectionMode(false);
      return next;
    });
  }, []);

  const startSelectionMode = useCallback((id: string) => {
    setIsSelectionMode(true);
    setSelectedIds(new Set([id]));
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
  }, []);

  const cancelSelection = useCallback(() => {
    setIsSelectionMode(false);
    setSelectedIds(new Set());
  }, []);

  useEffect(() => {
    const backAction = () => {
      if (isSelectionMode) {
        cancelSelection();
        return true;
      }
      return false;
    };
    const backHandler = BackHandler.addEventListener('hardwareBackPress', backAction);
    return () => backHandler.remove();
  }, [isSelectionMode, cancelSelection]);

  // Selection Mode Header
  const renderSelectionHeader = () => (
    <View className="mb-4 px-6 bg-white dark:bg-black" style={{ paddingTop: 74 + insets.top }}>
      <Animated.View
        entering={FadeIn.duration(300)}
        exiting={FadeOut.duration(200)}
        className="flex-row items-center mb-4"
      >
        <TouchableOpacity
          onPress={cancelSelection}
          className="w-10 h-10 items-center justify-center rounded-2xl bg-gray-50 dark:bg-zinc-900 mr-4"
        >
          <X size={20} color="#64748b" />
        </TouchableOpacity>
        <View>
          <Typography
            variant="h2"
            className="text-gray-900 dark:text-white font-black leading-tight"
          >
            {selectedIds.size} {t.library.selected}
          </Typography>
          <Typography
            variant="caption"
            className="text-indigo-500 font-bold uppercase text-[10px] tracking-widest leading-none"
          >
            {t.library.managementMode}
          </Typography>
        </View>
      </Animated.View>

      <View className="flex-row items-center justify-between h-12">
        <TouchableOpacity className="flex-1 h-full bg-gray-50 dark:bg-zinc-900 rounded-2xl flex-row items-center justify-center mr-2 border border-gray-100 dark:border-zinc-800">
          <FolderInput size={18} color="#6366f1" strokeWidth={2} />
          <Typography className="ml-2 text-indigo-500 font-bold text-[12px]">
            {t.library.move}
          </Typography>
        </TouchableOpacity>
        <TouchableOpacity className="w-12 h-full bg-red-50 dark:bg-red-900/10 rounded-2xl items-center justify-center border border-red-100 dark:border-red-900/20">
          <X size={18} color="#ef4444" strokeWidth={2} />
        </TouchableOpacity>
      </View>
    </View>
  );

  // Normal Mode Header (Search Bar)
  const renderNormalHeader = () => (
    <View className="mb-4 px-6 bg-white dark:bg-black" style={{ paddingTop: 74 + insets.top }}>
      <View className="flex-row items-center bg-gray-50 dark:bg-zinc-900 px-4 h-12 rounded-2xl border border-gray-100 dark:border-zinc-800">
        <Search size={18} color="#94a3b8" />
        <Typography className="ml-3 text-gray-400 font-medium text-[14px]">
          {t.library.searchIn} {translatedLabel}
        </Typography>
      </View>
    </View>
  );

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      {!isSelectionMode && (
        <GlassHeader
          title={translatedLabel}
          subtitle={`${t.library.description} / ${translatedLabel}`}
          leftAction={{
            icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
            onPress: () => router.back(),
            label: t.common.back,
          }}
          rightAction={{
            icon: <Plus size={20} color="#64748b" />,
            onPress: () => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium),
            label: t.common.add,
          }}
        />
      )}

      <FlashList<DocItem>
        data={folderDocs}
        renderItem={({ item }) => (
          <RagDocItem
            item={item}
            isSelected={selectedIds.has(item.id)}
            isSelectionMode={isSelectionMode}
            selectionProgress={selectionProgress}
            onPress={() =>
              isSelectionMode
                ? toggleSelection(item.id)
                : Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light)
            }
            onLongPress={() => !isSelectionMode && startSelectionMode(item.id)}
            onDelete={() => showToast(t.common.error, 'error')}
            showToast={showToast}
          />
        )}
        keyExtractor={(item) => item.id}
        ListHeaderComponent={isSelectionMode ? renderSelectionHeader : renderNormalHeader}
        estimatedItemSize={78}
        contentContainerStyle={{ paddingBottom: 100 }}
        {...({} as any)}
      />
    </PageLayout>
  );
}
