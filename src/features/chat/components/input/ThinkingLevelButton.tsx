import React, { useState, useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Check, Lightbulb } from 'lucide-react-native';
import * as Haptics from '../../../../lib/haptics';
import { useChatStore } from '../../../../store/chat-store';
import { useApiStore } from '../../../../store/api-store';
import { useAgentStore } from '../../../../store/agent-store';
import { Typography, GlassBottomSheet } from '../../../../components/ui';
import { supportsThinkingConfig } from '../../../../lib/llm/model-utils';

interface ThinkingLevelButtonProps {
  sessionId: string;
  isDark: boolean;
  activeModelId?: string;
  displayName?: string;
}

export const ThinkingLevelButton: React.FC<ThinkingLevelButtonProps> = React.memo(({ 
  sessionId, 
  isDark, 
  activeModelId, 
  displayName 
}) => {
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const updateSessionOptions = useChatStore((s) => s.updateSessionOptions);
  const providers = useApiStore((s) => s.providers);
  const [visible, setVisible] = useState(false);

  const modelConfig = useMemo(() => {
    if (!activeModelId) return null;
    for (const p of providers) {
      const m = p.models.find(mod => mod.uuid === activeModelId || mod.id === activeModelId);
      if (m) return m;
    }
    return null;
  }, [providers, activeModelId]);

  const isSupported = useMemo(() => activeModelId ? supportsThinkingConfig(activeModelId) : false, [activeModelId]);

  if (!session || !activeModelId || !isSupported) return null;

  const level = session.options?.thinkingLevel || 'high';
  const nameToCheck = (displayName || modelConfig?.name || '').toLowerCase();
  const isFlash = nameToCheck.includes('gemini') && nameToCheck.includes('flash');

  const options = [
    { value: 'minimal', label: '极速', desc: '限制模型最大限度地少用 token 进行思考 (仅限 Flash)。', disabled: !isFlash },
    { value: 'low', label: '轻量', desc: '限制模型使用较少的 token 进行思考，适合不需要进行大量推理的简单任务。' },
    { value: 'medium', label: '均衡', desc: '提供了一种均衡方法，适合中等复杂程度的任务 (仅限 Flash)。', disabled: !isFlash },
    { value: 'high', label: '深度', desc: '允许模型使用更多的 token 进行思考，适合需要深度推理的复杂提示。' },
  ];

  const getIcon = (l: string, size: number = 14) => {
    switch (l) {
      case 'minimal': return <Lightbulb size={size} color="#9ca3af" strokeWidth={2.5} />;
      case 'low': return <Lightbulb size={size} color="#22c55e" strokeWidth={2.5} />;
      case 'medium': return <Lightbulb size={size} color="#eab308" strokeWidth={2.5} />;
      case 'high': return <Lightbulb size={size} color="#a855f7" strokeWidth={2.5} />;
      default: return <Lightbulb size={size} color={isDark ? '#52525b' : '#a1a1aa'} />;
    }
  };

  const getLabel = (l: string) => {
    switch (l) {
      case 'minimal': return 'MINI';
      case 'low': return 'LOW';
      case 'medium': return 'MED';
      case 'high': return 'HIGH';
      default: return l.toUpperCase();
    }
  };

  const handleSelect = (l: string) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      updateSessionOptions(sessionId, { thinkingLevel: l as any });
      setVisible(false);
    }, 10);
  };

  return (
    <>
      <TouchableOpacity
        onPress={() => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
          }, 10);
          setVisible(true);
        }}
        activeOpacity={0.6}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 8,
          paddingVertical: 2,
          borderRadius: 10,
          backgroundColor: 'rgba(0,0,0,0.03)',
          gap: 4,
          marginRight: 6,
        }}
      >
        {getIcon(level, 10)}
        <Typography
          className="text-[9px] font-black uppercase tracking-tight"
          style={{
            color: level === 'minimal' ? '#9ca3af' : level === 'low' ? '#22c55e' : level === 'medium' ? '#eab308' : '#a855f7',
          }}
        >
          {getLabel(level)}
        </Typography>
      </TouchableOpacity>

      <GlassBottomSheet
        visible={visible}
        onClose={() => setVisible(false)}
        title="思考等级 (Reasoning Effort)"
        subtitle="为模型生成回答指定思考预算"
        height="auto"
      >
        <View style={{ paddingHorizontal: 16, paddingBottom: 24, gap: 12 }}>
          <Typography
            variant="body"
            style={{ color: isDark ? '#a1a1aa' : '#52525b', lineHeight: 20, marginBottom: 8 }}
          >
            调整模型在生成回答时的思考深度与 token 消耗预算。Pro 模型仅支持 <Typography style={{ fontWeight: '700', color: isDark ? '#fff' : '#000' }}>轻量</Typography> 与 <Typography style={{ fontWeight: '700', color: isDark ? '#fff' : '#000' }}>深度</Typography> 模式。
          </Typography>

          <View style={{ gap: 12 }}>
            {options.map((opt) => (
              <TouchableOpacity
                key={opt.value}
                disabled={opt.disabled}
                onPress={() => !opt.disabled && handleSelect(opt.value)}
                style={{
                  flexDirection: 'row',
                  padding: 16,
                  backgroundColor: isDark ? '#27272a' : '#fff',
                  borderRadius: 16,
                  borderWidth: 1.5,
                  borderColor: level === opt.value ? '#a855f7' : (isDark ? '#3f3f46' : '#e4e4e7'),
                  opacity: opt.disabled ? 0.4 : 1,
                  gap: 14
                }}
              >
                <View style={{ paddingTop: 2 }}>
                  {getIcon(opt.value, 22)}
                </View>
                <View style={{ flex: 1, gap: 4 }}>
                  <Typography
                    style={{
                      fontSize: 16,
                      fontWeight: '700',
                      color: opt.disabled ? (isDark ? '#52525b' : '#d4d4d8') : (isDark ? '#fff' : '#000')
                    }}
                  >
                    {opt.label} {opt.disabled && <Typography style={{ fontSize: 12, fontWeight: '400', color: isDark ? '#52525b' : '#a1a1aa' }}>(不支持)</Typography>}
                  </Typography>
                  <Typography
                    style={{
                      fontSize: 13,
                      color: isDark ? '#a1a1aa' : '#52525b',
                      lineHeight: 18
                    }}
                  >
                    {opt.desc}
                  </Typography>
                </View>
                {level === opt.value && (
                  <View style={{ justifyContent: 'center' }}>
                    <Check size={20} color="#a855f7" strokeWidth={3} />
                  </View>
                )}
              </TouchableOpacity>
            ))}
          </View>
        </View>
      </GlassBottomSheet>
    </>
  );
});
