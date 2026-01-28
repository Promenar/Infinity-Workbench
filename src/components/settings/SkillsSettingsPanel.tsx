import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, Text, TextInput } from 'react-native';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, Easing } from 'react-native-reanimated';
import { Typography } from '../ui/Typography';
import { Switch } from '../ui/Switch';
import { skillRegistry } from '../../lib/skills/registry';
import { Skill } from '../../types/skills';
import { useSettingsStore } from '../../store/settings-store';
import { useI18n } from '../../lib/i18n';
import { Minus, Plus, Check } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from '../../lib/haptics';
import { SettingsSection } from '../../features/settings/components/SettingsSection';
import { SettingsItem } from '../../features/settings/components/SettingsItem';
import { Colors } from '../../theme/colors';
import { FloatingCodeEditorModal } from '../ui/FloatingCodeEditorModal';

export const SkillsSettingsPanel: React.FC = () => {
    const { t } = useI18n();
    const { colors, isDark } = useTheme();
    const themeColors = isDark ? Colors.dark : Colors.light;

    const {
        skillsConfig,
        setSkillEnabled,
        maxLoopCount,
        setMaxLoopCount,
        executionMode,
        setExecutionMode,
        alphaVantageApiKey, // 🔑
        setAlphaVantageApiKey // 🔑
    } = useSettingsStore();

    // ⚡ Local State for Performance (Debounced Persistence)
    const [localCount, setLocalCount] = useState(maxLoopCount || 20);
    // 🧠 Ref to track latest value synchronously for stopAdjusting closure safety
    const localCountRef = React.useRef(maxLoopCount || 20);
    const intervalRef = React.useRef<NodeJS.Timeout | null>(null);
    const timeoutRef = React.useRef<NodeJS.Timeout | null>(null);

    // Tab Animation Local State
    const [containerWidth, setContainerWidth] = useState(0);
    const tabProgress = useSharedValue(0);

    const animatedIndicatorStyle = useAnimatedStyle(() => {
        const halfWidth = containerWidth ? (containerWidth - 8) / 2 : 0;
        return {
            transform: [{ translateX: tabProgress.value * halfWidth }],
            width: halfWidth,
        };
    });

    // Sync from store only when store updates externally
    useEffect(() => {
        if (maxLoopCount !== undefined) {
            setLocalCount(maxLoopCount);
            localCountRef.current = maxLoopCount;
        }
    }, [maxLoopCount]);

    // Clean up timers
    useEffect(() => {
        return () => {
            if (intervalRef.current) clearInterval(intervalRef.current);
            if (timeoutRef.current) clearTimeout(timeoutRef.current);
        };
    }, []);

    const adjustValue = (delta: number) => {
        const prev = localCountRef.current;
        let newVal = prev + delta;

        if (newVal > 100) newVal = 100; // Cap at 100 (∞)
        if (newVal < 1) newVal = 1;

        if (newVal !== prev) {
            localCountRef.current = newVal;
            setLocalCount(newVal);
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        }
    };

    const startAdjusting = (delta: number) => {
        // 1. Immediate trigger
        adjustValue(delta);

        // 2. Setup rapid fire delay
        timeoutRef.current = setTimeout(() => {
            intervalRef.current = setInterval(() => {
                adjustValue(delta);
            }, 80); // 80ms interval for smooth fast-forward
        }, 400); // 400ms hold delay before rapid fire
    };

    const stopAdjusting = () => {
        if (timeoutRef.current) clearTimeout(timeoutRef.current);
        if (intervalRef.current) clearInterval(intervalRef.current);

        // ⚡ Commit to store (Persistence) using the latest Ref value
        setMaxLoopCount(localCountRef.current);
    };

    const [skills, setSkills] = useState<Skill[]>([]);

    useEffect(() => {
        const allSkills = skillRegistry.getAllSkills();
        setSkills(allSkills);
    }, []);

    const handleToggleSkill = (id: string, value: boolean) => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        setSkillEnabled(id, value);
    };

    // Use localCount for logic instead of store value
    const isInfinite = localCount >= 100;

    const [activeTab, setActiveTab] = useState<'preset' | 'user'>('preset');

    useEffect(() => {
        tabProgress.value = withTiming(activeTab === 'preset' ? 0 : 1, {
            duration: 300,
            easing: Easing.bezier(0.25, 0.1, 0.25, 1),
        });
    }, [activeTab]);
    const [editorVisible, setEditorVisible] = useState(false);
    const [configModalVisible, setConfigModalVisible] = useState(false);
    const [editingSkillId, setEditingSkillId] = useState<string | null>(null);
    const [editingCode, setEditingCode] = useState('');
    const [editingConfig, setEditingConfig] = useState('');

    // Dynamic refresh
    const refreshSkills = async () => {
        const allSkills = skillRegistry.getAllSkills();
        setSkills([...allSkills]); // Force new reference
    };

    // Subscribe to registry in some way? Or just poll/refresh on focus?
    // For now, refresh on mount and deletions.

    const filteredSkills = skills.filter(s => {
        if (activeTab === 'preset') return !s.category || s.category === 'preset';
        return s.category === 'user' || s.category === 'model';
    });

    const handleDeleteSkill = async (id: string) => {
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const { skillRegistry } = require('../../lib/skills/registry');
        await UserSkillsStorage.deleteSkill(id);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        await skillRegistry.reloadUserSkills();
        refreshSkills();
    };

    const handleEditSkill = async (id: string) => {
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const storedSkills = await UserSkillsStorage.loadSkills();
        const target = storedSkills.find((s: any) => s.id === id);
        if (target) {
            setEditingSkillId(id);
            setEditingCode(target.code);
            setEditorVisible(true);
        }
    };

    const handleConfigureSkill = async (id: string) => {
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const storedSkills = await UserSkillsStorage.loadSkills();
        const target = storedSkills.find((s: any) => s.id === id);
        if (target) {
            setEditingSkillId(id);
            setEditingConfig(target.configJson || '{\n  "apiKey": ""\n}');
            setConfigModalVisible(true);
        }
    };

    const handleSaveCode = async (newCode: string) => {
        if (!editingSkillId) return;
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const { skillRegistry } = require('../../lib/skills/registry');

        const storedSkills = await UserSkillsStorage.loadSkills();
        const target = storedSkills.find((s: any) => s.id === editingSkillId);

        if (target) {
            await UserSkillsStorage.saveSkill({
                ...target,
                code: newCode
            });
            await skillRegistry.reloadUserSkills();
            refreshSkills();
            setEditorVisible(false);
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        }
    };

    const handleSaveConfig = async (newConfig: string) => {
        if (!editingSkillId) return;

        // Validate JSON
        try {
            JSON.parse(newConfig);
        } catch (e) {
            alert('Invalid JSON');
            // In a better world we use a Toast or inline error. 
            // For now, let's just let the modal stay open or handle it? 
            // The Modal implementation might not handle validation blockage.
            // But let's assume the user knows JSON.
            // Actually, let's just save. The storage safely ignores bad JSON on load.
        }

        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const { skillRegistry } = require('../../lib/skills/registry');

        const storedSkills = await UserSkillsStorage.loadSkills();
        const target = storedSkills.find((s: any) => s.id === editingSkillId);

        if (target) {
            await UserSkillsStorage.saveSkill({
                ...target,
                configJson: newConfig
            });
            await skillRegistry.reloadUserSkills();
            refreshSkills();
            setConfigModalVisible(false);
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        }
    };

    return (
        <View className="flex-1">
            {/* Logic Control Group */}
            <SettingsSection title={t.settings.skillsSettings.title}>
                {/* Loop Limit */}
                <View className="flex-col px-4 py-4 border-b border-gray-100 dark:border-zinc-800 gap-3">
                    <View className="flex-row justify-between items-center">
                        <View className="flex-1 mr-4">
                            <Typography variant="h3" className="text-gray-900 dark:text-white text-sm font-bold">
                                {t.settings.skillsSettings.loopLimit}
                            </Typography>
                            <Typography variant="caption" className="text-gray-500 dark:text-gray-400 mt-1 text-[10px]">
                                {t.settings.skillsSettings.loopLimitDesc}
                            </Typography>
                        </View>

                        <View
                            className="flex-row items-center rounded-2xl px-1 py-1"
                            style={{ backgroundColor: isDark ? '#27272a' : '#f3f4f6' }}
                        >
                            <TouchableOpacity
                                onPressIn={() => startAdjusting(-1)}
                                onPressOut={stopAdjusting}
                                className="w-10 h-10 items-center justify-center rounded-xl bg-white dark:bg-zinc-700 shadow-sm"
                                style={{ opacity: localCount <= 1 ? 0.4 : 1 }}
                                disabled={localCount <= 1}
                            >
                                <Minus size={18} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>

                            <Text style={{
                                marginHorizontal: 16,
                                minWidth: 24,
                                textAlign: 'center',
                                fontSize: 18,
                                fontWeight: '700',
                                color: isInfinite ? '#ef4444' : (isDark ? '#fff' : '#000') // 红色强调无限
                            }}>
                                {isInfinite ? '∞' : localCount}
                            </Text>

                            <TouchableOpacity
                                onPressIn={() => startAdjusting(1)}
                                onPressOut={stopAdjusting}
                                className="w-10 h-10 items-center justify-center rounded-xl bg-white dark:bg-zinc-700 shadow-sm"
                                style={{ opacity: isInfinite ? 0.4 : 1 }}
                                disabled={isInfinite}
                            >
                                <Plus size={18} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                        </View>
                    </View>

                    {/* ⚠️ Infinite Warning */}
                    {isInfinite && (
                        <View className="bg-red-50 dark:bg-red-900/20 px-3 py-2 rounded-lg border border-red-100 dark:border-red-900/50">
                            <Typography className="text-xs text-red-600 dark:text-red-400 font-medium">
                                ⚠️ 警告：无限模式可能导致 Token 消耗激增。请确保您的 API 额度充足，并密切关注任务状态。
                            </Typography>
                        </View>
                    )}

                    {/* ⚠️ Low Limit Warning */}
                    {localCount < 10 && (
                        <View className="bg-yellow-50 dark:bg-yellow-900/20 px-3 py-2 rounded-lg border border-yellow-100 dark:border-yellow-900/50">
                            <Typography className="text-xs text-yellow-600 dark:text-yellow-400 font-medium">
                                ⚠️ 注意：过低的思考轮数限制 ({localCount}) 可能导致复杂任务非正常中断。建议至少设置为 10。
                            </Typography>
                        </View>
                    )}
                </View>

            </SettingsSection>

            {/* Individual Skills */}
            <SettingsSection title={t.settings.agentSkills.title}>
                {/* Segmented Control Tab */}
                {/* Segmented Control Tab (Reanimated) */}
                <View
                    className="mx-4 mb-4"
                    onLayout={(e) => setContainerWidth(e.nativeEvent.layout.width)}
                    style={{
                        flexDirection: 'row',
                        backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : '#f3f4f6', // zinc-900/gray-100 with opacity
                        padding: 4,
                        borderRadius: 16,
                        position: 'relative',
                        height: 48,
                    }}
                >
                    {/* Animated Indicator */}
                    <Animated.View
                        style={[
                            {
                                position: 'absolute',
                                top: 4,
                                left: 4,
                                bottom: 4,
                                borderRadius: 12,
                                backgroundColor: isDark ? 'rgba(63, 63, 70, 0.8)' : '#ffffff', // zinc-700 / white
                                shadowColor: '#000',
                                shadowOffset: { width: 0, height: 1 },
                                shadowOpacity: isDark ? 0 : 0.05,
                                shadowRadius: 2,
                            },
                            animatedIndicatorStyle
                        ]}
                    />

                    <TouchableOpacity
                        onPress={() => {
                            setActiveTab('preset');
                            Haptics.selectionAsync();
                        }}
                        style={{
                            flex: 1,
                            alignItems: 'center',
                            justifyContent: 'center',
                            zIndex: 1,
                        }}
                    >
                        <Text style={{
                            fontSize: 14,
                            fontWeight: activeTab === 'preset' ? '700' : '500',
                            color: activeTab === 'preset' ? (isDark ? '#fff' : '#000') : (isDark ? '#a1a1aa' : '#6b7280'), // zinc-400 / gray-500
                        }}>
                            {t.settings.agentSkills.preset || '预设技能'}
                        </Text>
                    </TouchableOpacity>

                    <TouchableOpacity
                        onPress={() => {
                            setActiveTab('user');
                            Haptics.selectionAsync();
                        }}
                        style={{
                            flex: 1,
                            alignItems: 'center',
                            justifyContent: 'center',
                            zIndex: 1,
                        }}
                    >
                        <Text style={{
                            fontSize: 14,
                            fontWeight: activeTab === 'user' ? '700' : '500',
                            color: activeTab === 'user' ? (isDark ? '#fff' : '#000') : (isDark ? '#a1a1aa' : '#6b7280'),
                        }}>
                            {t.settings.agentSkills.user || '自定义'}
                        </Text>
                    </TouchableOpacity>
                </View>

                {filteredSkills.map((skill, index) => {
                    const isEnabled = skillsConfig[skill.id] !== false;
                    const isLast = index === filteredSkills.length - 1;
                    const isHighRisk = skill.isHighRisk;
                    const isUser = skill.category === 'user' || skill.category === 'model';

                    const localizedName = (!isUser && t.skills.names[skill.id as keyof typeof t.skills.names]) || skill.name;
                    const localizedDesc = (!isUser && t.skills.descriptions[skill.id as keyof typeof t.skills.descriptions]) || skill.description;

                    return (
                        <View
                            key={skill.id}
                            style={[
                                { padding: 12 },
                                !isLast && { borderBottomWidth: 1, borderBottomColor: themeColors.borderDefault }
                            ]}
                        >
                            <View className="flex-row justify-between items-center mb-1">
                                <View className="flex-1 mr-4">
                                    <View className="flex-row items-center gap-2">
                                        <Text style={{
                                            fontSize: 14,
                                            fontWeight: '700',
                                            color: themeColors.textPrimary
                                        }}>
                                            {localizedName}
                                        </Text>

                                        {/* Tags */}
                                        <View className="bg-gray-200 dark:bg-zinc-800 px-1.5 py-0.5 rounded">
                                            <Text style={{
                                                fontSize: 9,
                                                fontFamily: 'monospace',
                                                color: themeColors.textTertiary
                                            }}>
                                                {skill.id}
                                            </Text>
                                        </View>

                                        {isHighRisk && (
                                            <View className="bg-red-100 dark:bg-red-900/40 px-1.5 py-0.5 rounded">
                                                <Text style={{ fontSize: 9, color: isDark ? '#f87171' : '#dc2626' }}>HIGH RISK</Text>
                                            </View>
                                        )}
                                    </View>
                                </View>
                                <Switch
                                    value={isEnabled}
                                    onValueChange={(val) => handleToggleSkill(skill.id, val)}
                                />
                            </View>

                            <Text style={{
                                fontSize: 10,
                                color: themeColors.textSecondary,
                                lineHeight: 14,
                                marginBottom: 4
                            }}>
                                {localizedDesc}
                            </Text>

                            {/* User Skill Actions */}
                            {isUser && (
                                <View className="flex-row gap-3 mt-2">
                                    <TouchableOpacity
                                        onPress={() => handleEditSkill(skill.id)}
                                        className="bg-gray-100 dark:bg-zinc-800 px-3 py-1.5 rounded-md"
                                    >
                                        <Text className="text-xs font-semibold text-blue-600 dark:text-blue-400">编辑代码</Text>
                                    </TouchableOpacity>
                                    <TouchableOpacity
                                        onPress={() => handleConfigureSkill(skill.id)}
                                        className="bg-gray-100 dark:bg-zinc-800 px-3 py-1.5 rounded-md"
                                    >
                                        <Text className="text-xs font-semibold text-purple-600 dark:text-purple-400">配置参数</Text>
                                    </TouchableOpacity>
                                    <TouchableOpacity
                                        onPress={() => handleDeleteSkill(skill.id)}
                                        className="bg-red-50 dark:bg-red-900/20 px-3 py-1.5 rounded-md"
                                    >
                                        <Text className="text-xs font-semibold text-red-600 dark:text-red-400">删除</Text>
                                    </TouchableOpacity>
                                </View>
                            )}
                        </View>
                    );
                })}

                {filteredSkills.length === 0 && (
                    <View className="items-center py-10">
                        <Typography color="secondary">当前分类下暂无技能。</Typography>
                    </View>
                )}
            </SettingsSection>

            {/* Code Editor Modal */}
            <FloatingCodeEditorModal
                visible={editorVisible}
                initialContent={editingCode}
                title={`编辑: ${editingSkillId}`}
                onClose={() => setEditorVisible(false)}
                onSave={handleSaveCode}
                warningMessage="修改工具代码可能导致功能异常。请确保返回有效的结果对象。"
            />

            {/* Config Editor Modal */}
            <FloatingCodeEditorModal
                visible={configModalVisible}
                initialContent={editingConfig}
                title={`配置: ${editingSkillId}`}
                onClose={() => setConfigModalVisible(false)}
                onSave={handleSaveConfig}
                warningMessage="请填入 JSON 格式的默认参数（例如 API Key）。这些参数将在运行时自动合并。"
            />
        </View>
    );
};
