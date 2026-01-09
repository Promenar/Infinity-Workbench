import React, { useState, useEffect } from 'react';
import { View, Modal, TextInput, TouchableOpacity, Text } from 'react-native';
import { Typography } from '../ui/Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';
import { X, Save, Trash2 } from 'lucide-react-native';
import { graphStore } from '../../lib/rag/graph-store';

interface KGEdgeEditModalProps {
    visible: boolean;
    edge: { id: string; label: string; from: string; to: string } | null;
    onClose: () => void;
    onSave: () => void;
}

export const KGEdgeEditModal: React.FC<KGEdgeEditModalProps> = ({
    visible,
    edge,
    onClose,
    onSave,
}) => {
    const { isDark, colors } = useTheme();
    const { t } = useI18n();
    const [relation, setRelation] = useState('');

    useEffect(() => {
        if (edge) {
            setRelation(edge.label || '');
        }
    }, [edge]);

    const handleSave = async () => {
        if (!edge || !relation.trim()) return;
        try {
            await graphStore.updateEdge(edge.id, { relation: relation.trim() });
            onSave();
            onClose();
        } catch (e) {
            console.error('Failed to update edge:', e);
        }
    };

    const handleDelete = async () => {
        if (!edge) return;
        try {
            await graphStore.deleteEdge(edge.id);
            onSave();
            onClose();
        } catch (e) {
            console.error('Failed to delete edge:', e);
        }
    };

    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
            <View className="flex-1 bg-black/50 justify-center items-center px-6">
                <View className="bg-white dark:bg-zinc-900 w-full rounded-3xl p-6 shadow-xl border border-gray-100 dark:border-zinc-800">

                    {/* Header */}
                    <View className="flex-row justify-between items-center mb-6">
                        <Typography variant="h3" className="text-gray-900 dark:text-white font-bold">
                            {t.rag.kg.relation}
                        </Typography>
                        <TouchableOpacity onPress={onClose} className="p-2 bg-gray-100 dark:bg-zinc-800 rounded-full">
                            <X size={20} color={isDark ? '#FFF' : '#000'} />
                        </TouchableOpacity>
                    </View>

                    {/* Form */}
                    <View className="gap-4">
                        <View>
                            <Typography variant="label" className="mb-2 text-gray-700 dark:text-gray-300">
                                {t.rag.kg.relation}
                            </Typography>
                            <TextInput
                                value={relation}
                                onChangeText={setRelation}
                                className="bg-gray-50 dark:bg-zinc-800 px-4 py-3 rounded-xl text-base text-gray-900 dark:text-white"
                                placeholder="e.g. related_to, contains..."
                                placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                            />
                        </View>
                    </View>

                    {/* Actions */}
                    <View className="flex-row gap-3 mt-8">
                        <TouchableOpacity
                            onPress={handleDelete}
                            className="flex-1 bg-red-50 dark:bg-red-900/20 py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                        >
                            <Trash2 size={18} color="#ef4444" />
                            <Text className="font-bold text-red-600 dark:text-red-400">{t.rag.kg.deleteEdge}</Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            onPress={handleSave}
                            style={{ backgroundColor: colors[500] }}
                            className="flex-[2] py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                        >
                            <Save size={18} color="white" />
                            <Text className="font-bold text-white">{t.rag.kg.saveChanges}</Text>
                        </TouchableOpacity>
                    </View>

                </View>
            </View>
        </Modal>
    );
};
