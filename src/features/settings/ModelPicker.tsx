import React, { useMemo, useState } from 'react';
import { View, Text, TouchableOpacity, Modal, TextInput } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { X, Search, Check, Cpu, Server } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';
import { useApiStore, ModelConfig, ProviderConfig } from '../../store/api-store';
import * as Haptics from 'expo-haptics';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown } from 'react-native-reanimated';

// 使用 any 绕过某些环境下 FlashList 的类型检测问题
const TypedFlashList = FlashList as any;

interface ModelPickerProps {
    visible: boolean;
    onClose: () => void;
    onSelect: (uuid: string) => void;
    selectedUuid?: string;
    title: string;
    filterType?: 'chat' | 'reasoning' | 'image' | 'embedding';
}

export const ModelPicker: React.FC<ModelPickerProps> = ({
    visible,
    onClose,
    onSelect,
    selectedUuid,
    title,
    filterType
}) => {
    const { theme } = useTheme();
    const { t } = useI18n();
    const { providers } = useApiStore();
    const [searchQuery, setSearchQuery] = useState('');

    const allModels = useMemo(() => {
        const models: (ModelConfig & { providerName: string })[] = [];
        providers.forEach(p => {
            p.models.forEach(m => {
                if (m.enabled) {
                    if (!filterType || m.type === filterType || (filterType === 'chat' && !m.type)) {
                        models.push({ ...m, providerName: p.name });
                    }
                }
            });
        });
        return models;
    }, [providers, filterType]);

    const filteredModels = useMemo(() => {
        if (!searchQuery) return allModels;
        const q = searchQuery.toLowerCase();
        return allModels.filter(m =>
            m.name.toLowerCase().includes(q) ||
            m.id.toLowerCase().includes(q) ||
            m.providerName.toLowerCase().includes(q)
        );
    }, [allModels, searchQuery]);

    const renderItem = ({ item }: { item: ModelConfig & { providerName: string } }) => {
        const isSelected = item.uuid === selectedUuid;
        return (
            <TouchableOpacity
                onPress={() => {
                    setTimeout(() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                        onSelect(item.uuid);
                        onClose();
                    }, 10);
                }}
                style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    padding: 16,
                    backgroundColor: theme === 'dark' ? (isSelected ? '#27272a' : 'transparent') : (isSelected ? '#f3f4f6' : 'transparent'),
                    borderBottomWidth: 1,
                    borderBottomColor: theme === 'dark' ? '#27272a' : '#f3f4f6'
                }}
            >
                <View style={{ flex: 1 }}>
                    <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
                        {item.name}
                    </Text>
                    <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 4 }}>
                        <Server size={12} color="#9ca3af" />
                        <Text style={{ fontSize: 12, color: '#9ca3af', marginLeft: 4 }}>
                            {item.providerName}
                        </Text>
                    </View>
                </View>
                {isSelected && <Check size={20} color="#6366f1" />}
            </TouchableOpacity>
        );
    };

    return (
        <Modal
            visible={visible}
            animationType="none"
            transparent={true}
            onRequestClose={onClose}
        >
            <View style={{ flex: 1, justifyContent: 'flex-end' }}>
                <Animated.View
                    entering={FadeIn}
                    exiting={FadeOut}
                    style={{
                        position: 'absolute',
                        top: 0, left: 0, right: 0, bottom: 0,
                        backgroundColor: 'rgba(0,0,0,0.5)'
                    }}
                >
                    <TouchableOpacity
                        activeOpacity={1}
                        style={{ flex: 1 }}
                        onPress={onClose}
                    />
                </Animated.View>

                <Animated.View
                    entering={SlideInDown.duration(350)}
                    exiting={SlideOutDown.duration(250)}
                    style={{
                        height: '80%',
                        backgroundColor: theme === 'dark' ? '#111' : '#fff',
                        borderTopLeftRadius: 32,
                        borderTopRightRadius: 32,
                        paddingTop: 20
                    }}
                >
                    <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 24, marginBottom: 20 }}>
                        <View>
                            <Text style={{ fontSize: 20, fontWeight: '900', color: theme === 'dark' ? '#fff' : '#111' }}>
                                {title}
                            </Text>
                            <Text style={{ fontSize: 12, color: '#9ca3af', marginTop: 2 }}>
                                {t.settings.modelPresets.select}
                            </Text>
                        </View>
                        <TouchableOpacity onPress={onClose} style={{ padding: 8 }}>
                            <X size={24} color={theme === 'dark' ? '#9ca3af' : '#6b7280'} />
                        </TouchableOpacity>
                    </View>

                    <View style={{ paddingHorizontal: 24, marginBottom: 16 }}>
                        <View style={{
                            flexDirection: 'row',
                            alignItems: 'center',
                            backgroundColor: theme === 'dark' ? '#18181b' : '#f3f4f6',
                            borderRadius: 16,
                            paddingHorizontal: 16,
                            height: 48,
                            borderWidth: 1,
                            borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb'
                        }}>
                            <Search size={18} color="#9ca3af" />
                            <TextInput
                                placeholder={t.settings.modelSettings.searchPlaceholder}
                                placeholderTextColor="#9ca3af"
                                value={searchQuery}
                                onChangeText={setSearchQuery}
                                style={{ flex: 1, marginLeft: 12, fontSize: 16, color: theme === 'dark' ? '#fff' : '#111' }}
                            />
                        </View>
                    </View>

                    {allModels.length === 0 ? (
                        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 40 }}>
                            <Cpu size={48} color="#9ca3af" style={{ opacity: 0.3, marginBottom: 16 }} />
                            <Text style={{ fontSize: 16, color: '#9ca3af', textAlign: 'center' }}>
                                暂无可用模型，请先在“服务商管理”中启用模型。
                            </Text>
                        </View>
                    ) : (
                        <TypedFlashList
                            data={filteredModels}
                            renderItem={renderItem}
                            estimatedItemSize={72}
                            keyExtractor={(item: any) => item.uuid}
                            contentContainerStyle={{ paddingBottom: 40 }}
                        />
                    )}
                </Animated.View>
            </View>
        </Modal>
    );
};
