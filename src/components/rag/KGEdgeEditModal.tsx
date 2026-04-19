import React, { useState, useEffect } from 'react';
import { View, Modal, TextInput, TouchableOpacity, Text, ActivityIndicator, Alert, KeyboardAvoidingView, Platform } from 'react-native';
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
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (edge) {
            setRelation(edge.label || '');
        }
    }, [edge]);

    const handleSave = async () => {
        if (!edge) return;
        if (!relation.trim()) {
            Alert.alert(t.common.error, t.rag.kg.relationRequired || 'Relation label is required');
            return;
        }
        setSaving(true);
        try {
            await graphStore.updateEdge(edge.id, { relation: relation.trim() });
            onSave();
            onClose();
        } catch (e: any) {
            console.error('Failed to update edge:', e);
            Alert.alert(t.common.error, `${t.rag.kg.saveFailed || 'Failed to save'}: ${e.message || 'Unknown error'}`);
        } finally {
            setSaving(false);
        }
    };

    const handleDelete = async () => {
        if (!edge) return;
        Alert.alert(
            t.common.confirm,
            t.rag.kg.deleteEdgeConfirm || 'Are you sure you want to delete this link?',
            [
                { text: t.common.cancel, style: 'cancel' },
                {
                    text: t.common.delete,
                    style: 'destructive',
                    onPress: async () => {
                        setSaving(true);
                        try {
                            await graphStore.deleteEdge(edge.id);
                            onSave();
                            onClose();
                        } catch (e: any) {
                            console.error('Failed to delete edge:', e);
                            Alert.alert(t.common.error, `${t.rag.kg.deleteFailed || 'Failed to delete'}: ${e.message}`);
                        } finally {
                            setSaving(false);
                        }
                    }
                }
            ]
        );
    };

    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
            <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} className="flex-1">
            <View className="flex-1 bg-black/50 justify-center items-center px-6">
                <View className="bg-white/80 dark:bg-zinc-900/60 w-full rounded-3xl p-6 shadow-xl border border-indigo-50 dark:border-indigo-500/10">

                    {/* Header */}
                    <View className="flex-row justify-between items-center mb-6">
                        <Typography variant="h3" className="text-gray-900 dark:text-white font-bold">
                            {t.rag.kg.relation} // Relation Label
                        </Typography>
                        <TouchableOpacity onPress={onClose} disabled={saving} className="p-2 bg-gray-100 dark:bg-zinc-800 rounded-full">
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
                                editable={!saving}
                            />
                        </View>
                    </View>

                    {/* Actions */}
                    <View className="flex-row gap-3 mt-8">
                        <TouchableOpacity
                            onPress={handleDelete}
                            disabled={saving}
                            className="flex-1 bg-red-50 dark:bg-red-900/20 py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                            style={{ opacity: saving ? 0.5 : 1 }}
                        >
                            <Trash2 size={18} color="#ef4444" />
                            <Text className="font-bold text-red-600 dark:text-red-400">{t.rag.kg.deleteEdge}</Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            onPress={handleSave}
                            disabled={saving}
                            style={{ backgroundColor: colors[500], opacity: saving ? 0.7 : 1 }}
                            className="flex-[2] py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                        >
                            {saving ? (
                                <ActivityIndicator color="white" size="small" />
                            ) : (
                                <>
                                    <Save size={18} color="white" />
                                    <Text className="font-bold text-white">{t.rag.kg.saveChanges}</Text>
                                </>
                            )}
                        </TouchableOpacity>
                    </View>

                </View>
            </View>
            </KeyboardAvoidingView>
        </Modal>
    );
};
