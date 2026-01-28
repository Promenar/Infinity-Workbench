import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, Text, TextInput } from 'react-native';
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
                {skills.map((skill, index) => {
                    const isEnabled = skillsConfig[skill.id] !== false;
                    const isLast = index === skills.length - 1;

                    const localizedName = t.skills.names[skill.id as keyof typeof t.skills.names] || skill.name;
                    const localizedDesc = t.skills.descriptions[skill.id as keyof typeof t.skills.descriptions] || skill.description;

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
                                        <View className="bg-gray-200 dark:bg-zinc-800 px-1.5 py-0.5 rounded">
                                            <Text style={{
                                                fontSize: 9,
                                                fontFamily: 'monospace',
                                                color: themeColors.textTertiary
                                            }}>
                                                {skill.id}
                                            </Text>
                                        </View>
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
                                lineHeight: 14
                            }}>
                                {localizedDesc}
                            </Text>

                            {/* 💸 Alpha Vantage API Key Configuration */}
                            {skill.id === 'query_financial_data' && isEnabled && (
                                <View className="mt-3 bg-gray-50 dark:bg-zinc-800/50 p-3 rounded-lg border border-gray-100 dark:border-zinc-700/50">
                                    <View className="flex-row items-center justify-between mb-2">
                                        <Text style={{
                                            fontSize: 11,
                                            fontWeight: '600',
                                            color: themeColors.textSecondary
                                        }}>
                                            API Configuration
                                        </Text>
                                        <View className="bg-blue-100 dark:bg-blue-900/30 px-1.5 py-0.5 rounded">
                                            <Text style={{ fontSize: 9, color: isDark ? '#60a5fa' : '#2563eb' }}>
                                                Required
                                            </Text>
                                        </View>
                                    </View>
                                    <View className="flex-row items-center gap-2">
                                        <TextInput
                                            value={alphaVantageApiKey || ''}
                                            onChangeText={setAlphaVantageApiKey}
                                            placeholder="Enter Alpha Vantage API Key"
                                            placeholderTextColor={isDark ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.3)"}
                                            style={{
                                                flex: 1,
                                                height: 36,
                                                backgroundColor: isDark ? 'rgba(0,0,0,0.2)' : '#fff',
                                                borderRadius: 8,
                                                paddingHorizontal: 10,
                                                fontSize: 12,
                                                color: themeColors.textPrimary,
                                                borderWidth: 1,
                                                borderColor: isDark ? 'rgba(255,255,255,0.1)' : '#e5e7eb'
                                            }}
                                            autoCapitalize="none"
                                            autoCorrect={false}
                                        />
                                    </View>
                                    <Text style={{
                                        marginTop: 6,
                                        fontSize: 10,
                                        color: themeColors.textTertiary
                                    }}>
                                        Get a free key from: https://www.alphavantage.co/support/#api-key
                                    </Text>
                                </View>
                            )}
                        </View>
                    );
                })}

                {skills.length === 0 && (
                    <View className="items-center py-10">
                        <Typography color="secondary">No skills registered.</Typography>
                    </View>
                )}
            </SettingsSection>
        </View>
    );
};
