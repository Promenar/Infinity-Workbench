import React, { useMemo } from 'react';
import { View, TouchableOpacity, ScrollView } from 'react-native';
import { Typography } from '../ui';
import { ChevronRight, Home } from 'lucide-react-native';
import { RagFolder } from '../../types/rag';
import { useTheme } from '../../theme/ThemeProvider';

interface BreadcrumbsProps {
    currentFolderId: string | null;
    allFolders: RagFolder[];
    onNavigate: (folderId: string | null) => void;
}

export const Breadcrumbs: React.FC<BreadcrumbsProps> = ({
    currentFolderId,
    allFolders,
    onNavigate
}) => {
    const { isDark } = useTheme();

    // 计算路径链
    const path = useMemo(() => {
        const chain: { id: string | null; name: string }[] = [];
        let currentId = currentFolderId;

        // 防止死循环 (max depth 20)
        let depth = 0;
        const maxDepth = 20;

        while (currentId && depth < maxDepth) {
            const folder = allFolders.find(f => f.id === currentId);
            if (folder) {
                chain.unshift({ id: folder.id, name: folder.name });
                currentId = folder.parentId || null;
            } else {
                break;
            }
            depth++;
        }

        // Add Root
        chain.unshift({ id: null, name: '根目录' });

        return chain;
    }, [currentFolderId, allFolders]);

    return (
        <View className="h-12 border-b border-gray-100 dark:border-zinc-800 bg-white dark:bg-black w-full">
            <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ alignItems: 'center', paddingHorizontal: 16 }}
            >
                {path.map((item, index) => {
                    const isLast = index === path.length - 1;
                    const isRoot = item.id === null;

                    return (
                        <View key={item.id ?? 'root'} className="flex-row items-center">
                            {/* Separator */}
                            {index > 0 && (
                                <ChevronRight size={14} color="#9ca3af" style={{ marginHorizontal: 4 }} />
                            )}

                            {/* Item */}
                            <TouchableOpacity
                                onPress={() => onNavigate(item.id)}
                                disabled={isLast}
                                className={`flex-row items-center py-2 px-1 rounded-lg ${!isLast ? 'active:bg-gray-100 dark:active:bg-zinc-800' : ''}`}
                            >
                                {isRoot && (
                                    <Home
                                        size={14}
                                        color={isLast ? (isDark ? '#fff' : '#111') : '#6b7280'}
                                        style={{ marginRight: 4 }}
                                    />
                                )}

                                <Typography
                                    className={`text-sm font-semibold ${isLast
                                            ? 'text-gray-900 dark:text-white'
                                            : 'text-gray-500 dark:text-gray-400'
                                        }`}
                                >
                                    {isRoot ? '首页' : item.name}
                                </Typography>
                            </TouchableOpacity>
                        </View>
                    );
                })}
            </ScrollView>
        </View>
    );
};
