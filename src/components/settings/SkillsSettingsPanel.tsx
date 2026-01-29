import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, Text, TextInput } from 'react-native';
import { Switch } from '../ui/Switch';
import Animated, { useSharedValue, useAnimatedStyle, withTiming, Easing } from 'react-native-reanimated';
import { Typography } from '../ui/Typography';
import { useI18n } from '../../lib/i18n';
import { skillRegistry } from '../../lib/skills/registry';
import { Skill } from '../../types/skills';
import { useSettingsStore } from '../../store/settings-store';
import { Minus, Plus, Check, Server, RefreshCw, Trash2, Globe, AlertTriangle, ChevronRight, HardDrive, Cpu, Database } from 'lucide-react-native';
import { Marquee } from '../ui/Marquee';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from '../../lib/haptics';
import { SettingsSection } from '../../features/settings/components/SettingsSection';
import { Colors } from '../../theme/colors';
import { FloatingCodeEditorModal } from '../ui/FloatingCodeEditorModal';
import { useMcpStore } from '../../store/mcp-store';
import { McpBridge } from '../../lib/mcp/mcp-bridge';

export const SkillsSettingsPanel: React.FC = () => {
    const { t } = useI18n();
    const { isDark } = useTheme();
    const themeColors = isDark ? Colors.dark : Colors.light;

    const {
        skillsConfig,
        setSkillEnabled,
        maxLoopCount,
        setMaxLoopCount,
    } = useSettingsStore();

    // ⚡ Local State for Performance
    const [localCount, setLocalCount] = useState(maxLoopCount || 20);
    const localCountRef = React.useRef(maxLoopCount || 20);
    const intervalRef = React.useRef<NodeJS.Timeout | null>(null);
    const timeoutRef = React.useRef<NodeJS.Timeout | null>(null);

    // Tab Animation
    const [activeTab, setActiveTab] = useState<'preset' | 'user' | 'mcp'>('preset');
    const [containerWidth, setContainerWidth] = useState(0);
    const tabProgress = useSharedValue(0);

    const animatedIndicatorStyle = useAnimatedStyle(() => {
        const tabWidth = containerWidth ? (containerWidth - 8) / 3 : 0;
        return {
            transform: [{ translateX: tabProgress.value * (tabWidth * 2) }],
            width: tabWidth,
        };
    });

    useEffect(() => {
        let progress = 0;
        if (activeTab === 'user') progress = 0.5;
        else if (activeTab === 'mcp') progress = 1;

        tabProgress.value = withTiming(progress, {
            duration: 300,
            easing: Easing.bezier(0.25, 0.1, 0.25, 1),
        });
    }, [activeTab]);

    // Loop config logic
    useEffect(() => {
        if (maxLoopCount !== undefined) {
            setLocalCount(maxLoopCount);
            localCountRef.current = maxLoopCount;
        }
    }, [maxLoopCount]);

    const adjustValue = (delta: number) => {
        const prev = localCountRef.current;
        let newVal = prev + delta;
        if (newVal > 100) newVal = 100;
        if (newVal < 1) newVal = 1;
        if (newVal !== prev) {
            localCountRef.current = newVal;
            setLocalCount(newVal);
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        }
    };

    const startAdjusting = (delta: number) => {
        adjustValue(delta);
        timeoutRef.current = setTimeout(() => {
            intervalRef.current = setInterval(() => adjustValue(delta), 80);
        }, 400);
    };

    const stopAdjusting = () => {
        if (timeoutRef.current) clearTimeout(timeoutRef.current);
        if (intervalRef.current) clearInterval(intervalRef.current);
        setMaxLoopCount(localCountRef.current);
    };

    const [skills, setSkills] = useState<Skill[]>([]);
    const [editorVisible, setEditorVisible] = useState(false);
    const [configModalVisible, setConfigModalVisible] = useState(false);
    const [editingSkillId, setEditingSkillId] = useState<string | null>(null);
    const [editingCode, setEditingCode] = useState('');
    const [editingConfig, setEditingConfig] = useState('');

    useEffect(() => {
        setSkills(skillRegistry.getAllSkills());
    }, []);

    const refreshSkills = () => setSkills([...skillRegistry.getAllSkills()]);

    const handleToggleSkill = (id: string, value: boolean) => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        setSkillEnabled(id, value);
    };

    const handleDeleteSkill = async (id: string) => {
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        await UserSkillsStorage.deleteSkill(id);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        await skillRegistry.reloadUserSkills();
        refreshSkills();
    };

    const handleEditSkill = async (id: string) => {
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const stored = await UserSkillsStorage.loadSkills();
        const target = stored.find((s: any) => s.id === id);
        if (target) {
            setEditingSkillId(id);
            setEditingCode(target.code);
            setEditorVisible(true);
        }
    };

    const handleConfigureSkill = async (id: string) => {
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const stored = await UserSkillsStorage.loadSkills();
        const target = stored.find((s: any) => s.id === id);
        if (target) {
            setEditingSkillId(id);
            setEditingConfig(target.configJson || '{\n  "apiKey": ""\n}');
            setConfigModalVisible(true);
        }
    };

    const handleSaveCode = async (code: string) => {
        if (!editingSkillId) return;
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const stored = await UserSkillsStorage.loadSkills();
        const target = stored.find((s: any) => s.id === editingSkillId);
        if (target) {
            await UserSkillsStorage.saveSkill({ ...target, code });
            await skillRegistry.reloadUserSkills();
            refreshSkills();
            setEditorVisible(false);
        }
    };

    const handleSaveConfig = async (configJson: string) => {
        if (!editingSkillId) return;
        const { UserSkillsStorage } = require('../../lib/skills/storage');
        const stored = await UserSkillsStorage.loadSkills();
        const target = stored.find((s: any) => s.id === editingSkillId);
        if (target) {
            await UserSkillsStorage.saveSkill({ ...target, configJson });
            await skillRegistry.reloadUserSkills();
            refreshSkills();
            setConfigModalVisible(false);
        }
    };

    const isInfinite = localCount >= 100;
    const filteredSkills = skills.filter(s => {
        if (activeTab === 'preset') return !s.category || s.category === 'preset';
        if (activeTab === 'user') return s.category === 'user';
        // MCP 标签页由 McpServerManagement 处理，但如果不处理，这里也不应该返回工具给 user Tab
        return false;
    });

    return (
        <View className="flex-1">
            <SettingsSection title={t.settings.skillsSettings.title}>
                <View className="flex-col px-4 py-4 border-b border-gray-100 dark:border-zinc-800 gap-3">
                    <View className="flex-row justify-between items-center">
                        <View className="flex-1 mr-4">
                            <Typography variant="h3" className="text-sm font-bold">{t.settings.skillsSettings.loopLimit}</Typography>
                            <Typography variant="caption" color="secondary" className="text-[10px]">{t.settings.skillsSettings.loopLimitDesc}</Typography>
                        </View>
                        <View className="flex-row items-center rounded-2xl px-1 py-1 bg-gray-100 dark:bg-zinc-800">
                            <TouchableOpacity onPressIn={() => startAdjusting(-1)} onPressOut={stopAdjusting} className="w-10 h-10 items-center justify-center rounded-xl bg-white dark:bg-zinc-700">
                                <Minus size={18} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                            <Text style={{ marginHorizontal: 16, fontSize: 18, fontWeight: '700', color: isInfinite ? '#ef4444' : (isDark ? '#fff' : '#000') }}>
                                {isInfinite ? '∞' : localCount}
                            </Text>
                            <TouchableOpacity onPressIn={() => startAdjusting(1)} onPressOut={stopAdjusting} className="w-10 h-10 items-center justify-center rounded-xl bg-white dark:bg-zinc-700">
                                <Plus size={18} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                        </View>
                    </View>
                    {isInfinite && (
                        <View className="bg-red-50 dark:bg-red-900/20 px-3 py-2 rounded-lg border border-red-100 dark:border-red-900/50">
                            <Typography className="text-xs text-red-600 dark:text-red-400 font-medium">⚠️ 警告：无限模式可能导致 Token 消耗激增。</Typography>
                        </View>
                    )}
                </View>
            </SettingsSection>

            <SettingsSection title={t.settings.agentSkills.title}>
                <View className="mx-4 mb-4 flex-row p-1 rounded-2xl bg-gray-100 dark:bg-zinc-900/60 relative" onLayout={e => setContainerWidth(e.nativeEvent.layout.width)}>
                    <Animated.View style={[{ position: 'absolute', top: 4, left: 4, bottom: 4, borderRadius: 12, backgroundColor: isDark ? 'rgba(63, 63, 70, 0.8)' : '#fff' }, animatedIndicatorStyle]} />
                    <TouchableOpacity onPress={() => { setActiveTab('preset'); Haptics.selectionAsync(); }} className="flex-1 items-center justify-center h-10 z-10">
                        <Text style={{ fontSize: 12, fontWeight: activeTab === 'preset' ? '700' : '500', color: activeTab === 'preset' ? (isDark ? '#fff' : '#000') : (isDark ? '#a1a1aa' : '#6b7280') }}>{t.settings.agentSkills.preset || '预设'}</Text>
                    </TouchableOpacity>
                    <TouchableOpacity onPress={() => { setActiveTab('user'); Haptics.selectionAsync(); }} className="flex-1 items-center justify-center h-10 z-10">
                        <Text style={{ fontSize: 12, fontWeight: activeTab === 'user' ? '700' : '500', color: activeTab === 'user' ? (isDark ? '#fff' : '#000') : (isDark ? '#a1a1aa' : '#6b7280') }}>{t.settings.agentSkills.user || '自定义'}</Text>
                    </TouchableOpacity>
                    <TouchableOpacity onPress={() => { setActiveTab('mcp'); Haptics.selectionAsync(); }} className="flex-1 items-center justify-center h-10 z-10">
                        <Text style={{ fontSize: 12, fontWeight: activeTab === 'mcp' ? '700' : '500', color: activeTab === 'mcp' ? (isDark ? '#fff' : '#000') : (isDark ? '#a1a1aa' : '#6b7280') }}>MCP</Text>
                    </TouchableOpacity>
                </View>

                {activeTab === 'mcp' ? (
                    <McpServerManagement />
                ) : (
                    <>
                        {filteredSkills.map((skill, index) => {
                            const isEnabled = skillsConfig[skill.id] !== false;
                            const isLast = index === filteredSkills.length - 1;
                            const isUser = skill.category === 'user' || skill.category === 'model';
                            const locName = (!isUser && t.skills.names[skill.id as keyof typeof t.skills.names]) || skill.name;
                            const locDesc = (!isUser && t.skills.descriptions[skill.id as keyof typeof t.skills.descriptions]) || skill.description;

                            return (
                                <View key={skill.id} style={[{ padding: 12 }, !isLast && { borderBottomWidth: 1, borderBottomColor: themeColors.borderDefault }]}>
                                    <View className="flex-row justify-between items-center mb-1">
                                        <View className="flex-1 mr-4">
                                            <View className="flex-row items-center gap-2">
                                                <Text style={{ fontSize: 14, fontWeight: '700', color: themeColors.textPrimary }}>{locName}</Text>
                                                <View className="bg-gray-200 dark:bg-zinc-800 px-1.5 py-0.5 rounded">
                                                    <Text style={{ fontSize: 9, fontFamily: 'monospace', color: themeColors.textTertiary }}>{skill.id}</Text>
                                                </View>
                                            </View>
                                        </View>
                                        <Switch value={isEnabled} onValueChange={v => handleToggleSkill(skill.id, v)} />
                                    </View>
                                    <Typography variant="caption" color="secondary" className="text-[10px]">{locDesc}</Typography>
                                    {isUser && (
                                        <View className="flex-row gap-3 mt-2">
                                            <TouchableOpacity onPress={() => handleEditSkill(skill.id)}><Text className="text-xs text-blue-600">编辑</Text></TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleConfigureSkill(skill.id)}><Text className="text-xs text-purple-600">配置</Text></TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleDeleteSkill(skill.id)}><Text className="text-xs text-red-600">删除</Text></TouchableOpacity>
                                        </View>
                                    )}
                                </View>
                            );
                        })}
                        {filteredSkills.length === 0 && <View className="items-center py-10"><Typography color="secondary">暂无技能</Typography></View>}
                    </>
                )}
            </SettingsSection>

            <FloatingCodeEditorModal visible={editorVisible} initialContent={editingCode} title={`编辑: ${editingSkillId}`} onClose={() => setEditorVisible(false)} onSave={handleSaveCode} warningMessage="修改代码可能导致异常。" />
            <FloatingCodeEditorModal visible={configModalVisible} initialContent={editingConfig} title={`配置: ${editingSkillId}`} onClose={() => setConfigModalVisible(false)} onSave={handleSaveConfig} warningMessage="请填入 JSON 参数。" />
        </View>
    );
};

const McpServerManagement: React.FC = () => {
    const { isDark, colors } = useTheme();
    const { t } = useI18n();
    const themeColors = isDark ? Colors.dark : Colors.light;
    const { servers, addServer, removeServer, updateServer } = useMcpStore();
    const [newUrl, setNewUrl] = useState('');
    const [newName, setNewName] = useState('');

    const handleAdd = () => {
        if (!newUrl || !newName) return;
        addServer({ id: Date.now().toString(), name: newName, url: newUrl, enabled: true, defaultIncluded: true });
        setNewUrl(''); setNewName('');
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    };

    const placeholderColor = isDark ? 'rgba(255,255,255,0.4)' : 'rgba(0,0,0,0.3)';

    return (
        <View className="px-4 py-4">
            <View className="mb-6 p-4 rounded-3xl bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800">
                <Typography variant="h3" className="mb-4 text-xs opacity-50 uppercase tracking-widest font-bold">{t.settings.skillsSettings.addServer}</Typography>

                <TextInput
                    value={newName}
                    onChangeText={setNewName}
                    placeholder={t.settings.skillsSettings.serverName}
                    className="mb-3 p-4 rounded-2xl bg-white dark:bg-zinc-800 text-sm text-zinc-900 dark:text-zinc-100 border border-transparent focus:border-indigo-500"
                />

                <TextInput
                    value={newUrl}
                    onChangeText={setNewUrl}
                    placeholder={t.settings.skillsSettings.serverUrl}
                    className="mb-4 p-4 rounded-2xl bg-white dark:bg-zinc-800 text-sm text-zinc-900 dark:text-zinc-100 border border-transparent focus:border-indigo-500"
                />

                <TouchableOpacity
                    onPress={handleAdd}
                    disabled={!newName || !newUrl}
                    className="w-full bg-sky-500 p-4 rounded-2xl items-center shadow-sm active:opacity-80"
                    style={{ opacity: (!newName || !newUrl) ? 0.5 : 1 }}
                >
                    <Text className="text-white font-black text-sm uppercase tracking-tighter">{t.settings.skillsSettings.confirmAdd}</Text>
                </TouchableOpacity>
            </View>

            {servers.map(server => (
                <View key={server.id} className="mb-4 p-5 rounded-3xl bg-white dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 shadow-sm">
                    <View className="flex-row justify-between items-center mb-4">
                        <View className="flex-row items-center flex-1 mr-3">
                            <View style={{ padding: 8, borderRadius: 12, backgroundColor: server.status === 'connected' ? 'rgba(16, 185, 129, 0.1)' : 'rgba(239, 68, 68, 0.1)' }}>
                                <Server size={18} color={server.status === 'connected' ? '#10b981' : '#ef4444'} />
                            </View>
                            <View className="flex-1 ml-3">
                                <Typography variant="body" className="font-bold dark:text-zinc-100" numberOfLines={1}>{server.name}</Typography>
                                <Marquee
                                    text={server.url}
                                    className="text-[10px] dark:text-zinc-400 font-medium"
                                    duration={5000}
                                    style={{ height: 16, marginTop: 2, opacity: 0.8 }}
                                />
                            </View>
                        </View>
                        <View className="flex-row gap-1">
                            <TouchableOpacity onPress={() => McpBridge.syncServer(server.id)} className="p-2 bg-gray-50 dark:bg-zinc-800 rounded-xl">
                                <RefreshCw size={18} color={colors[500]} />
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => removeServer(server.id)} className="p-2 bg-red-50 dark:bg-red-900/10 rounded-xl">
                                <Trash2 size={18} color="#ef4444" />
                            </TouchableOpacity>
                        </View>
                    </View>

                    {server.error && (
                        <View className="bg-red-50 dark:bg-red-950/40 p-3 rounded-2xl mb-3 border border-red-100 dark:border-red-900/30">
                            <Typography className="text-[11px] text-red-600 dark:text-red-300 font-bold">ERROR: {server.error}</Typography>
                        </View>
                    )}

                    {/* 🆕 工具列表展示 */}
                    {server.status === 'connected' && (
                        <View className="mb-4">
                            <View className="flex-row items-center mb-2 gap-1.5">
                                <Cpu size={12} color={isDark ? '#a1a1aa' : '#71717a'} />
                                <Typography className="text-[10px] font-bold opacity-60 uppercase tracking-tighter">已发现工具</Typography>
                            </View>
                            <View className="flex-row flex-wrap gap-1.5">
                                {skillRegistry.getAllSkills()
                                    .filter(s => s.mcpServerId === server.id)
                                    .map(s => (
                                        <View key={s.id} className="bg-gray-100 dark:bg-zinc-800 px-2 py-1 rounded-lg border border-gray-200/50 dark:border-zinc-700/50">
                                            <Text style={{ fontSize: 9, fontWeight: '600', color: themeColors.textSecondary }}>{s.name}</Text>
                                        </View>
                                    ))
                                }
                                {skillRegistry.getAllSkills().filter(s => s.mcpServerId === server.id).length === 0 && (
                                    <Typography className="text-[10px] opacity-40 italic">暂无可用工具</Typography>
                                )}
                            </View>
                        </View>
                    )}

                    <View className="mb-4 bg-gray-50 dark:bg-zinc-800/50 p-4 rounded-2xl">
                        <View className="flex-row justify-between items-center mb-3">
                            <View>
                                <Typography className="text-[11px] font-bold opacity-70 dark:text-zinc-300">{t.settings.skillsSettings.callInterval}</Typography>
                                <Typography className="text-[9px] opacity-40 dark:text-zinc-500">仅限该服务器 (0 = 不启用)</Typography>
                            </View>
                            <View className="flex-row items-center rounded-xl px-1 py-1 bg-white dark:bg-zinc-700 border border-gray-100 dark:border-zinc-600">
                                <TouchableOpacity
                                    onPress={() => {
                                        const current = server.callInterval || 0;
                                        if (current > 0) updateServer(server.id, { callInterval: current - 1 });
                                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                    }}
                                    className="w-7 h-7 items-center justify-center rounded-lg"
                                >
                                    <Minus size={14} color={isDark ? '#fff' : '#000'} />
                                </TouchableOpacity>
                                <View className="px-3 items-center min-w-[40px]">
                                    <Text style={{ fontSize: 13, fontWeight: '700', color: (server.callInterval || 0) > 0 ? colors[500] : (isDark ? '#fff' : '#000') }}>
                                        {server.callInterval || 0}
                                    </Text>
                                    <Text className="text-[8px] opacity-50 dark:text-zinc-400 font-bold uppercase">{t.settings.skillsSettings.callIntervalUnit}</Text>
                                </View>
                                <TouchableOpacity
                                    onPress={() => {
                                        const current = server.callInterval || 0;
                                        updateServer(server.id, { callInterval: current + 1 });
                                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                    }}
                                    className="w-7 h-7 items-center justify-center rounded-lg"
                                >
                                    <Plus size={14} color={isDark ? '#fff' : '#000'} />
                                </TouchableOpacity>
                            </View>
                        </View>

                        <View className="flex-row justify-between items-center pt-2 border-t border-gray-100 dark:border-zinc-700/50">
                            <View className="flex-row items-center gap-4">
                                <View className="flex-row items-center gap-1.5">
                                    <Switch value={server.enabled} onValueChange={val => updateServer(server.id, { enabled: val })} />
                                    <Typography className="text-[11px] font-bold opacity-70 dark:text-zinc-300">{t.settings.skillsSettings.enabled}</Typography>
                                </View>
                                <View className="flex-row items-center gap-1.5">
                                    <Switch value={server.defaultIncluded} onValueChange={val => updateServer(server.id, { defaultIncluded: val })} />
                                    <Typography className="text-[11px] font-bold opacity-70 dark:text-zinc-300">{t.settings.skillsSettings.default}</Typography>
                                </View>
                            </View>
                            <View className="flex-row items-center px-2 py-1 rounded-lg bg-green-50 dark:bg-green-900/10">
                                <Typography className="text-[9px] font-bold text-green-600 dark:text-green-500 uppercase">
                                    {(() => {
                                        const statusKey = `status${server.status.charAt(0).toUpperCase() + server.status.slice(1)}` as keyof typeof t.settings.skillsSettings;
                                        const val = t.settings.skillsSettings[statusKey];
                                        return typeof val === 'string' ? val : server.status.toUpperCase();
                                    })()}
                                </Typography>
                            </View>
                        </View>
                    </View>
                </View>
            ))}
        </View>
    );
};
