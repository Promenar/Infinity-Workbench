import React, { useState } from 'react';
import { View, TouchableOpacity, Text, ScrollView } from 'react-native';
import { Switch } from '../../../components/ui/Switch';
import { useTheme } from '../../../theme/ThemeProvider';
import { useChatStore } from '../../../store/chat-store';
import { useMcpStore } from '../../../store/mcp-store';
import { skillRegistry } from '../../../lib/skills/registry';
import { Typography } from '../../../components/ui/Typography';
import { GlassBottomSheet } from '../../../components/ui/GlassBottomSheet';
import { useI18n } from '../../../lib/i18n';
import { Zap, Shield, PlayCircle, Server, Wrench, ChevronDown } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';

interface ExecutionModeSelectorProps {
    sessionId: string;
}

export const ExecutionModeSelector = ({ sessionId }: ExecutionModeSelectorProps) => {
    const { isDark, colors } = useTheme();
    const { t } = useI18n();
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
    const { setExecutionMode, toggleMcpServer, toggleSkill, updateSessionOptions } = useChatStore();
    const { servers } = useMcpStore();
    const [visible, setVisible] = useState(false);

    if (!session) return null;
    const mode = session.executionMode || 'semi';
    const toolsEnabled = session.options?.toolsEnabled ?? true;

    const renderModeButton = (m: 'auto' | 'semi' | 'manual', label: string, Icon: any) => {
        const isActive = mode === m;
        const localizedLabel = t.settings.skillsSettings.modes[m];
        return (
            <TouchableOpacity
                onPress={() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    setExecutionMode(sessionId, m);
                }}
                className={'flex-1 flex-col items-center justify-center p-4 rounded-2xl'}
                style={{
                    backgroundColor: isActive ? colors[500] : (isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)')
                }}
            >
                <Icon size={20} color={isActive ? '#fff' : (isDark ? '#a1a1aa' : '#6b7280')} strokeWidth={isActive ? 2.5 : 2} />
                <Text style={{ marginTop: 6, fontSize: 10, fontWeight: '900', letterSpacing: 0.5, color: isActive ? '#fff' : (isDark ? '#a1a1aa' : '#6b7280') }}>
                    {localizedLabel.includes(' ') ? localizedLabel.split(' ')[1].toUpperCase() : localizedLabel.toUpperCase()}
                </Text>
            </TouchableOpacity>
        );
    };

    const activeMcpIds = session.activeMcpServerIds || [];
    const activeSkillIds = session.activeSkillIds || [];
    const hasActiveTools = activeMcpIds.length > 0 || activeSkillIds.length > 0;

    return (
        <>
            <TouchableOpacity
                onPress={() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    setVisible(true);
                }}
                className="flex-row items-center bg-zinc-100 dark:bg-zinc-800/60 px-3 py-1.5 rounded-full gap-2 border border-transparent dark:border-zinc-700/50"
            >
                {!toolsEnabled ? (
                    <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: '#71717a' }} />
                ) : (
                    <>
                        {mode === 'auto' && <Zap size={14} color={colors[500]} strokeWidth={2.5} />}
                        {mode === 'semi' && <Shield size={14} color="#d97706" strokeWidth={2.5} />}
                        {mode === 'manual' && <PlayCircle size={14} color="#059669" strokeWidth={2.5} />}
                    </>
                )}

                <Typography variant="caption" className="font-black text-[10px] text-zinc-600 dark:text-zinc-300">
                    {toolsEnabled ? mode.toUpperCase() : 'OFF'}
                </Typography>
                <ChevronDown size={12} color={isDark ? '#a1a1aa' : '#71717a'} />
            </TouchableOpacity>

            <GlassBottomSheet
                visible={visible}
                onClose={() => setVisible(false)}
                title={t.settings.toolbox.title}
                subtitle={t.settings.toolbox.subtitle}
                height="80%"
            >
                <ScrollView
                    showsVerticalScrollIndicator={false}
                    contentContainerStyle={{ paddingHorizontal: 24, paddingBottom: 60 }}
                >
                    {/* 1. Master Toggle: Enable/Disable Capabilities */}
                    <View className="mb-8">
                        <View className="flex-row items-center gap-2 mb-4">
                            <Wrench size={14} color={colors[500]} />
                            <Typography variant="h3" className="text-xs opacity-60 dark:text-zinc-400 uppercase tracking-widest font-bold">
                                {t.agent.modelConfig} {/* Reuse 'Model Config' or similar? Or hardcode 'CAPABILITIES' */}
                                CAPABILITIES
                            </Typography>
                        </View>

                        <View
                            className={'flex-row items-center justify-between p-5 rounded-3xl border shadow-sm'}
                            style={{
                                backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fff',
                                borderColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)'
                            }}
                        >
                            <View className="flex-1 mr-4">
                                <Typography variant="body" className="font-bold dark:text-zinc-100">
                                    {t.settings.skillsSettings.enabled /* reused 'Enabled' */}
                                    {t.settings.agentSkills.title /* 'Agent Skills' */}
                                </Typography>
                                <Typography variant="caption" className="text-[10px] opacity-60 dark:text-zinc-400 mt-0.5">
                                    {toolsEnabled
                                        ? t.settings.skillsSettings.modeDescriptions.auto /* Reuse 'Fully automated...' text or generic 'Tools are active' */
                                        : t.settings.skillsSettings.description /* 'Disable to...' */
                                    }
                                    {/* Let's use hardcoded English/Chinese fallback for specificity if specific keys missing */}
                                    {toolsEnabled ? 'Tools & Skills are active.' : 'All tool calls are disabled for this session.'}
                                </Typography>
                            </View>
                            <Switch
                                value={toolsEnabled}
                                onValueChange={(v) => {
                                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                    updateSessionOptions(sessionId, { toolsEnabled: v });
                                }}
                            />
                        </View>
                    </View>

                    {/* Execution Mode Selection (Only show if enabled) */}
                    <View className="mb-10" style={{ opacity: toolsEnabled ? 1 : 0.4, pointerEvents: toolsEnabled ? 'auto' : 'none' }}>
                        <Typography variant="h3" className="mb-4 text-xs opacity-60 dark:text-zinc-400 uppercase tracking-widest font-bold">{t.settings.toolbox.executionMode}</Typography>
                        <View className="flex-row gap-3">
                            {renderModeButton('auto', 'Auto', Zap)}
                            {renderModeButton('semi', 'Semi', Shield)}
                            {renderModeButton('manual', 'Manual', PlayCircle)}
                        </View>
                    </View>

                    {/* MCP Servers Selection */}
                    {servers.filter(s => s.enabled).length > 0 && (
                        <View className="mb-10" style={{ opacity: toolsEnabled ? 1 : 0.4, pointerEvents: toolsEnabled ? 'auto' : 'none' }}>
                            <View className="flex-row items-center gap-2 mb-4">
                                <Server size={14} color={colors[500]} />
                                <Typography variant="h3" className="text-xs opacity-60 dark:text-zinc-400 uppercase tracking-widest font-bold">{t.settings.toolbox.activeMcp}</Typography>
                            </View>
                            <View className="gap-3">
                                {servers.filter(s => s.enabled).map(server => {
                                    const isActive = activeMcpIds.includes(server.id);
                                    return (
                                        <TouchableOpacity
                                            key={server.id}
                                            onPress={() => toggleMcpServer(sessionId, server.id)}
                                            className={'flex-row items-center justify-between p-5 rounded-3xl border shadow-sm'}
                                            style={{
                                                backgroundColor: isActive ? (isDark ? 'rgba(99, 102, 241, 0.1)' : '#ffffff') : (isDark ? 'rgba(255,255,255,0.02)' : '#fff'),
                                                borderColor: isActive ? colors[500] : (isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)')
                                            }}
                                        >
                                            <View className="flex-1 mr-4">
                                                <Typography variant="body" className="font-bold dark:text-zinc-100">{server.name}</Typography>
                                                <Typography variant="caption" className="text-[10px] opacity-60 dark:text-zinc-400 mt-0.5" numberOfLines={1}>{server.url}</Typography>
                                            </View>
                                            <Switch
                                                value={isActive}
                                                onValueChange={() => toggleMcpServer(sessionId, server.id)}
                                            />
                                        </TouchableOpacity>
                                    );
                                })}
                            </View>
                        </View>
                    )}

                    {/* User Skills Selection */}
                    {skillRegistry.getAllSkills().filter(s => s.category === 'user').length > 0 && (
                        <View className="mb-4" style={{ opacity: toolsEnabled ? 1 : 0.4, pointerEvents: toolsEnabled ? 'auto' : 'none' }}>
                            <View className="flex-row items-center gap-2 mb-4">
                                <Wrench size={14} color="#ec4899" />
                                <Typography variant="h3" className="text-xs opacity-60 dark:text-zinc-400 uppercase tracking-widest font-bold">{t.settings.toolbox.sessionSkills}</Typography>
                            </View>
                            <View className="gap-3">
                                {skillRegistry.getAllSkills().filter(s => s.category === 'user').map(skill => {
                                    const isActive = activeSkillIds.includes(skill.id);
                                    return (
                                        <TouchableOpacity
                                            key={skill.id}
                                            onPress={() => toggleSkill(sessionId, skill.id)}
                                            className={'flex-row items-center justify-between p-5 rounded-3xl border shadow-sm'}
                                            style={{
                                                backgroundColor: isActive ? (isDark ? 'rgba(236, 72, 153, 0.1)' : '#ffffff') : (isDark ? 'rgba(255,255,255,0.02)' : '#fff'),
                                                borderColor: isActive ? '#ec4899' : (isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)')
                                            }}
                                        >
                                            <View className="flex-1 mr-4">
                                                <Typography variant="body" className="font-bold dark:text-zinc-100">{skill.name}</Typography>
                                                <Typography variant="caption" className="text-[10px] opacity-60 dark:text-zinc-400 mt-0.5" numberOfLines={1}>{skill.id}</Typography>
                                            </View>
                                            <Switch
                                                value={isActive}
                                                onValueChange={() => toggleSkill(sessionId, skill.id)}
                                            />
                                        </TouchableOpacity>
                                    );
                                })}
                            </View>
                        </View>
                    )}

                    {/* Explicit Close Button REMOVED as per user request */}
                </ScrollView>
            </GlassBottomSheet>
        </>
    );
};
