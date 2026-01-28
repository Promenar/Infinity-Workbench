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
    const { setExecutionMode, toggleMcpServer, toggleSkill } = useChatStore();
    const { servers } = useMcpStore();
    const [visible, setVisible] = useState(false);

    if (!session) return null;
    const mode = session.executionMode || 'semi';

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

    return (
        <>
            <TouchableOpacity
                onPress={() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    setVisible(true);
                }}
                className="flex-row items-center bg-zinc-100 dark:bg-zinc-800/60 px-3 py-1.5 rounded-full gap-2 border border-transparent dark:border-zinc-700/50"
            >
                {mode === 'auto' && <Zap size={14} color={colors[500]} strokeWidth={2.5} />}
                {mode === 'semi' && <Shield size={14} color="#d97706" strokeWidth={2.5} />}
                {mode === 'manual' && <PlayCircle size={14} color="#059669" strokeWidth={2.5} />}
                <Typography variant="caption" className="font-black text-[10px] text-zinc-600 dark:text-zinc-300">
                    {mode.toUpperCase()}
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
                    {/* Execution Mode Selection */}
                    <View className="mb-10">
                        <Typography variant="h3" className="mb-4 text-xs opacity-60 dark:text-zinc-400 uppercase tracking-widest font-bold">{t.settings.toolbox.executionMode}</Typography>
                        <View className="flex-row gap-3">
                            {renderModeButton('auto', 'Auto', Zap)}
                            {renderModeButton('semi', 'Semi', Shield)}
                            {renderModeButton('manual', 'Manual', PlayCircle)}
                        </View>
                    </View>

                    {/* MCP Servers Selection */}
                    {servers.filter(s => s.enabled).length > 0 && (
                        <View className="mb-10">
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
                                                backgroundColor: isActive ? (isDark ? 'rgba(99, 102, 241, 0.1)' : 'rgba(99, 102, 241, 0.05)') : (isDark ? 'rgba(255,255,255,0.02)' : '#fff'),
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
                        <View className="mb-4">
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
                                                backgroundColor: isActive ? (isDark ? 'rgba(236, 72, 153, 0.1)' : 'rgba(236, 72, 153, 0.05)') : (isDark ? 'rgba(255,255,255,0.02)' : '#fff'),
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
                </ScrollView>
            </GlassBottomSheet>
        </>
    );
};
