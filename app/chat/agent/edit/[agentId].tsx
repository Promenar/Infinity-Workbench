import React, { useState } from 'react';
import { View, ScrollView, TouchableOpacity, TextInput } from 'react-native';
import { PageLayout, Typography, Header } from '../../../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft, Save, Sparkles, BrainCircuit } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { useAgentStore } from '../../../../src/store/agent-store';
import { clsx } from 'clsx';

export default function AgentEditScreen() {
    const { agentId } = useLocalSearchParams<{ agentId: string }>();
    const router = useRouter();
    const { getAgent, updateAgent } = useAgentStore();
    const agent = getAgent(agentId);

    const [formData, setFormData] = useState({
        name: agent?.name || '',
        description: agent?.description || '',
        systemPrompt: agent?.systemPrompt || '',
        defaultModel: agent?.defaultModel || 'gpt-4o',
        temperature: agent?.params.temperature || 0.7,
    });

    const handleSave = () => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        updateAgent(agentId, {
            name: formData.name,
            description: formData.description,
            systemPrompt: formData.systemPrompt,
            defaultModel: formData.defaultModel,
            params: { ...agent?.params, temperature: formData.temperature }
        });
        router.back();
    };

    const handleDelete = () => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
        deleteAgent(agentId);
        router.push('/(tabs)/chat');
    };

    if (!agent) return null;

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />

            <Header
                title="Edit Assistant"
                subtitle={agent.name.toUpperCase()}
                leftAction={
                    <TouchableOpacity onPress={() => router.back()} className="p-2 -ml-2">
                        <ChevronLeft size={24} color="#64748b" />
                    </TouchableOpacity>
                }
                rightAction={
                    <TouchableOpacity onPress={handleSave} className="p-2 -mr-2">
                        <Save size={22} color="#6366f1" />
                    </TouchableOpacity>
                }
            />

            <ScrollView className="flex-1 px-6" contentContainerStyle={{ paddingTop: 20, paddingBottom: 100 }}>
                {/* Basic Info Group */}
                <Typography variant="label" className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3">Basic Information</Typography>
                <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
                    <Typography className="text-gray-900 dark:text-white font-bold mb-2">Name</Typography>
                    <TextInput
                        className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-gray-100 dark:border-zinc-800 mb-4"
                        value={formData.name}
                        onChangeText={(text) => setFormData({ ...formData, name: text })}
                    />

                    <Typography className="text-gray-900 dark:text-white font-bold mb-2">Short Description</Typography>
                    <TextInput
                        className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-gray-100 dark:border-zinc-800"
                        multiline
                        numberOfLines={2}
                        value={formData.description}
                        onChangeText={(text) => setFormData({ ...formData, description: text })}
                    />
                </View>

                {/* Personality Group */}
                <Typography variant="label" className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3 flex-row items-center">
                    <Sparkles size={10} color="#64748b" /> Personality (System Prompt)
                </Typography>
                <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
                    <TextInput
                        className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-4 rounded-xl border border-gray-100 dark:border-zinc-800 h-40"
                        multiline
                        textAlignVertical="top"
                        value={formData.systemPrompt}
                        onChangeText={(text) => setFormData({ ...formData, systemPrompt: text })}
                        placeholder="Define how this AI should behave..."
                    />
                </View>

                {/* Model Config Group */}
                <Typography variant="label" className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3 flex-row items-center">
                    Model Configuration
                </Typography>
                <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
                    <Typography className="text-gray-900 dark:text-white font-bold mb-3">Engine</Typography>
                    <View className="flex-row flex-wrap">
                        {['gpt-4o', 'claude-3-opus', 'deepseek-v3', 'llama-3.1'].map((m) => (
                            <TouchableOpacity
                                key={m}
                                onPress={() => setFormData({ ...formData, defaultModel: m })}
                                className={clsx(
                                    "px-4 py-2 rounded-full mr-2 mb-2 border",
                                    formData.defaultModel === m
                                        ? "bg-indigo-50 border-indigo-200 dark:bg-indigo-500/20 dark:border-indigo-500"
                                        : "bg-white border-gray-100 dark:bg-black dark:border-zinc-800"
                                )}
                            >
                                <Typography className={clsx(
                                    "text-[12px] font-bold",
                                    formData.defaultModel === m ? "text-indigo-600 dark:text-indigo-400" : "text-gray-500"
                                )}>{m.toUpperCase()}</Typography>
                            </TouchableOpacity>
                        ))}
                    </View>

                    <View className="mt-6">
                        <View className="flex-row justify-between items-center mb-2">
                            <Typography className="text-gray-900 dark:text-white font-bold">Creativity (Temp)</Typography>
                            <Typography className="text-indigo-500 font-black">{formData.temperature.toFixed(1)}</Typography>
                        </View>
                        <View className="h-2 bg-gray-200 dark:bg-zinc-800 rounded-full overflow-hidden">
                            <View
                                className="h-full bg-indigo-500"
                                style={{ width: `${formData.temperature * 100}%` }}
                            />
                        </View>
                        <View className="flex-row justify-between mt-2">
                            <Typography className="text-[10px] text-gray-400 font-bold">PRECISE</Typography>
                            <Typography className="text-[10px] text-gray-400 font-bold">CREATIVE</Typography>
                        </View>
                    </View>
                </View>

                {/* Delete Action */}
                <TouchableOpacity
                    className="bg-red-50 dark:bg-red-500/10 p-5 rounded-3xl border border-red-100 dark:border-red-900/30 items-center justify-center"
                    onPress={handleDelete}
                >
                    <Typography className="text-red-500 font-bold uppercase tracking-widest text-[12px]">Delete Assistant</Typography>
                </TouchableOpacity>
            </ScrollView>
        </PageLayout>
    );
}
