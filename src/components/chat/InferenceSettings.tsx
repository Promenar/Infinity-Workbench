import React, { useState } from 'react';
import { View, TextInput, TouchableOpacity } from 'react-native';
import { Typography } from '../ui/Typography';
import { ThemedSlider } from '../ui/Slider';
import { InferenceParams } from '../../types/chat';
import { useI18n } from '../../lib/i18n';
import { Sliders, Zap, Anchor, Sparkles } from 'lucide-react-native';

interface Props {
  params: InferenceParams;
  onUpdate: (params: InferenceParams) => void;
  agentDefaultParams?: InferenceParams;
}

export const InferenceSettings: React.FC<Props> = ({ params, onUpdate, agentDefaultParams }) => {
  const { t } = useI18n();

  // Helper to get effective value (session > agent > default)
  const getValue = (key: keyof InferenceParams, fallback: number) => {
    return params[key] ?? agentDefaultParams?.[key] ?? fallback;
  };

  const temperature = getValue('temperature', 0.7);
  const topP = getValue('topP', 1.0);
  const maxTokens = getValue('maxTokens', 4096);

  const applyPreset = React.useCallback(
    (preset: 'precise' | 'balanced' | 'creative') => {
      switch (preset) {
        case 'precise':
          onUpdate({ temperature: 0.1, topP: 0.9 });
          break;
        case 'balanced':
          onUpdate({ temperature: 0.7, topP: 1.0 });
          break;
        case 'creative':
          onUpdate({ temperature: 1.2, topP: 0.95 });
          break;
      }
    },
    [onUpdate],
  );

  const PRESETS = React.useMemo(
    () => [
      { id: 'precise', icon: Anchor, label: t.inference.precise, color: '#10b981' },
      { id: 'balanced', icon: Zap, label: t.inference.balanced, color: '#6366f1' },
      { id: 'creative', icon: Sparkles, label: t.inference.creative, color: '#f43f5e' },
    ],
    [t.inference.precise, t.inference.balanced, t.inference.creative],
  );

  return (
    <View className="space-y-6">
      {/* Presets Grid */}
      <View className="flex-row justify-between gap-3 px-1">
        {PRESETS.map((item) => (
          <TouchableOpacity
            key={item.id}
            onPress={() => applyPreset(item.id as any)}
            activeOpacity={0.7}
            className="flex-1 items-center bg-white dark:bg-black/40 p-3 rounded-2xl border border-gray-100 dark:border-white/5 shadow-sm active:bg-gray-50 dark:active:bg-white/10"
          >
            <item.icon size={16} color={item.color} className="mb-1.5" />
            <Typography variant="caption" className="font-bold text-gray-700 dark:text-gray-300">
              {item.label}
            </Typography>
          </TouchableOpacity>
        ))}
      </View>


      <View className="h-[1px] bg-gray-200/50 dark:bg-white/5 mt-2" />

      {/* Temperature */}
      <View className="px-1">
        <View className="flex-row justify-between items-center mb-4">
          <View className="flex-1 mr-4">
            <Typography className="font-bold text-base text-gray-900 dark:text-gray-100 mb-1">
              {t.inference.temperature}
            </Typography>
            <Typography variant="caption" className="text-gray-400 text-xs leading-4">
              {t.inference.temperatureDesc}
            </Typography>
          </View>
          <View className="bg-white dark:bg-black px-2.5 py-1 rounded-xl border border-gray-100 dark:border-white/5 shadow-sm">
            <Typography className="text-gray-900 dark:text-white font-mono font-bold">
              {temperature.toFixed(1)}
            </Typography>
          </View>
        </View>
        <ThemedSlider
          value={temperature}
          minimumValue={0}
          maximumValue={2.0}
          step={0.1}
          onValueChange={(val) => onUpdate({ ...params, temperature: parseFloat(val.toFixed(1)) })}
        />
      </View>

      <View className="h-[1px] bg-gray-100 dark:bg-white/5" />

      {/* Top P */}
      <View className="px-1">
        <View className="flex-row justify-between items-center mb-4">
          <View className="flex-1 mr-4">
            <Typography className="font-bold text-base text-gray-900 dark:text-gray-100 mb-1">
              {t.inference.topP}
            </Typography>
            <Typography variant="caption" className="text-gray-400 text-xs leading-4">
              {t.inference.topPDesc || 'Controls diversity via nucleus sampling'}
            </Typography>
          </View>
          <View className="bg-white dark:bg-black px-2.5 py-1 rounded-xl border border-gray-100 dark:border-white/5 shadow-sm">
            <Typography className="text-gray-900 dark:text-white font-mono font-bold">
              {topP.toFixed(2)}
            </Typography>
          </View>
        </View>
        <ThemedSlider
          value={topP}
          minimumValue={0}
          maximumValue={1.0}
          step={0.05}
          onValueChange={(val) => onUpdate({ ...params, topP: parseFloat(val.toFixed(2)) })}
        />
      </View>

      <View className="h-[1px] bg-gray-100 dark:bg-white/5" />

      {/* Max Tokens */}
      <View className="px-1">
        <View className="flex-row justify-between items-center">
          <View className="flex-1 mr-4">
            <Typography className="font-bold text-base text-gray-900 dark:text-gray-100 mb-1">
              {t.inference.maxTokens}
            </Typography>
            <Typography variant="caption" className="text-gray-400 text-xs leading-4">
              {t.inference.maxTokensDesc}
            </Typography>
          </View>
          <TextInput
            className="bg-white dark:bg-black px-3 py-2 rounded-xl text-right text-gray-900 dark:text-white font-mono font-bold min-w-[90px] border border-gray-100 dark:border-white/5 shadow-sm"
            value={maxTokens?.toString()}
            keyboardType="numeric"
            onChangeText={(text) => onUpdate({ ...params, maxTokens: parseInt(text) || undefined })}
            placeholder={t.inference.limit}
            placeholderTextColor="#9ca3af"
          />
        </View>
      </View>
    </View>
  );
};
