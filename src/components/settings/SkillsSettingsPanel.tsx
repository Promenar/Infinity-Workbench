import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, Text } from 'react-native';
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
        setExecutionMode
    } = useSettingsStore();

    const [skills, setSkills] = useState<Skill[]>([]);

    useEffect(() => {
        const allSkills = skillRegistry.getAllSkills();
        setSkills(allSkills);
    }, []);

    const handleToggleSkill = (id: string, value: boolean) => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        setSkillEnabled(id, value);
    };

    const handleLoopChange = (delta: number) => {
        const currentCount = maxLoopCount || 5;
        const newValue = Math.max(1, Math.min(20, currentCount + delta));
        if (newValue !== currentCount) {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            setMaxLoopCount(newValue);
        }
    };

    return (
        <View className="flex-1">
            {/* Logic Control Group */}
            <SettingsSection title={t.settings.skillsSettings.title}>
                {/* Loop Limit */}
                <View className="flex-row justify-between items-center px-4 py-4 border-b border-gray-100 dark:border-zinc-800">
                    <View className="flex-1 mr-4">
                        <Typography variant="h3" className="text-gray-900 dark:text-white text-base">
                            {t.settings.skillsSettings.loopLimit}
                        </Typography>
                        <Typography variant="caption" className="text-gray-500 dark:text-gray-400 mt-1">
                            {t.settings.skillsSettings.loopLimitDesc}
                        </Typography>
                    </View>

                    <View
                        className="flex-row items-center rounded-2xl px-1 py-1"
                        style={{ backgroundColor: isDark ? '#27272a' : '#f3f4f6' }}
                    >
                        <TouchableOpacity
                            onPress={() => handleLoopChange(-1)}
                            className="w-10 h-10 items-center justify-center rounded-xl bg-white dark:bg-zinc-700 shadow-sm"
                            style={{ opacity: (maxLoopCount || 5) <= 1 ? 0.4 : 1 }}
                            disabled={(maxLoopCount || 5) <= 1}
                        >
                            <Minus size={18} color={isDark ? '#fff' : '#000'} />
                        </TouchableOpacity>

                        <Text style={{
                            marginHorizontal: 16,
                            minWidth: 24,
                            textAlign: 'center',
                            fontSize: 18,
                            fontWeight: '700',
                            color: isDark ? '#fff' : '#000'
                        }}>
                            {maxLoopCount || 5}
                        </Text>

                        <TouchableOpacity
                            onPress={() => handleLoopChange(1)}
                            className="w-10 h-10 items-center justify-center rounded-xl bg-white dark:bg-zinc-700 shadow-sm"
                            style={{ opacity: (maxLoopCount || 5) >= 20 ? 0.4 : 1 }}
                            disabled={(maxLoopCount || 5) >= 20}
                        >
                            <Plus size={18} color={isDark ? '#fff' : '#000'} />
                        </TouchableOpacity>
                    </View>
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
                                { padding: 16 },
                                !isLast && { borderBottomWidth: 1, borderBottomColor: themeColors.borderDefault }
                            ]}
                        >
                            <View className="flex-row justify-between items-center mb-2">
                                <View className="flex-1 mr-4">
                                    <View className="flex-row items-center gap-2">
                                        <Text style={{
                                            fontSize: 16,
                                            fontWeight: '600',
                                            color: themeColors.textPrimary
                                        }}>
                                            {localizedName}
                                        </Text>
                                        <View className="bg-gray-200 dark:bg-zinc-800 px-2 py-0.5 rounded">
                                            <Text style={{
                                                fontSize: 10,
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
                                fontSize: 13,
                                color: themeColors.textSecondary,
                                lineHeight: 18
                            }}>
                                {localizedDesc}
                            </Text>
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
