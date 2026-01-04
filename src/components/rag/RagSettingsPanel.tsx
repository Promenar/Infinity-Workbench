import React from 'react';
import { View, Text, StyleSheet, TextInput, TouchableOpacity } from 'react-native';
import Slider from '@react-native-community/slider';
import { RagConfiguration } from '../../types/chat';
import { RotateCcw, Check, Sparkles, Code2, BookOpen } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';
import { useSettingsStore } from '../../store/settings-store';
import * as Haptics from '../../lib/haptics';

interface RagSettingsPanelProps {
  config?: RagConfiguration; // Make optional
  onUpdate: (config: RagConfiguration) => void;
  onReset?: () => void; // Optional reset action
  isGlobal?: boolean; // If true, hide "Reset to Global" (since it IS global)
}

/**
 * @deprecated 这个组件已被弃用，请使用 `features/settings/components/` 下的
 * `GlobalRagConfigPanel` 或 `AgentRagConfigPanel`。
 * 未来版本中此文件将被彻底移除。
 */
export const RagSettingsPanel: React.FC<RagSettingsPanelProps> = ({
  config,
  onUpdate,
  onReset,
  isGlobal = false,
}) => {
  React.useEffect(() => {
    console.warn('[RagSettingsPanel] 警告: 您正在使用已弃用的 RAG 设置组件。请迁移到通用 Panel。');
  }, []);
  const { isDark } = useTheme();
  const { t } = useI18n();
  const { globalRagConfig } = useSettingsStore();

  // 如果未传入配置，则显示全局配置作为预览
  const effectiveConfig = config || globalRagConfig;

  const updateField = <K extends keyof RagConfiguration>(key: K, value: RagConfiguration[K]) => {
    // 如果当前是继承状态（config 为空），则基于全局配置进行覆盖
    onUpdate({ ...effectiveConfig, [key]: value });
  };

  const handlePresetSelect = (docChunkSize: number, chunkOverlap: number) => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    onUpdate({
      ...effectiveConfig,
      docChunkSize,
      chunkOverlap,
    });
  };

  const textColor = isDark ? '#e2e8f0' : '#334155';
  const subTextColor = isDark ? '#94a3b8' : '#64748b';
  const descColor = isDark ? '#64748b' : '#94a3b8';
  const labelColor = isDark ? '#cbd5e1' : '#475569';
  const inputBg = isDark ? '#27272a' : '#f8fafc';
  const inputBorder = isDark ? '#3f3f46' : '#e2e8f0';

  const getActivePreset = () => {
    const { docChunkSize, chunkOverlap } = effectiveConfig;
    if (docChunkSize === 800 && chunkOverlap === 100) return 'balanced';
    if (docChunkSize === 1500 && chunkOverlap === 300) return 'writing';
    if (docChunkSize === 1000 && chunkOverlap === 200) return 'code';
    return null;
  };

  const activePreset = getActivePreset();

  return (
    <View style={styles.container}>
      <View style={styles.headerRow}>
        <View>
          <Text style={[styles.sectionTitle, { color: textColor }]}>{t.rag.title}</Text>
          {!config && !isGlobal && (
            <Text style={{ fontSize: 10, color: '#6366f1', fontWeight: '600', marginTop: 2 }}>
              (继自全局默认)
            </Text>
          )}
        </View>
        {onReset && !isGlobal && config && (
          <TouchableOpacity
            onPress={onReset}
            style={[styles.resetButton, { backgroundColor: isDark ? '#312e81' : '#e0e7ff' }]}
          >
            <RotateCcw size={12} color={isDark ? '#818cf8' : '#6366f1'} />
            <Text style={[styles.resetText, { color: isDark ? '#818cf8' : '#6366f1' }]}>
              {t.rag.resetToGlobal}
            </Text>
          </TouchableOpacity>
        )}
      </View>

      {/* Context Window */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.contextWindow}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.contextWindow} 轮
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.contextWindowDesc}</Text>
        <Slider
          value={effectiveConfig.contextWindow}
          onValueChange={(val: number) => updateField('contextWindow', Math.floor(val))}
          minimumValue={5}
          maximumValue={50}
          step={1}
          minimumTrackTintColor="#6366f1"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#6366f1"
        />
      </View>

      <View
        style={{
          height: 1,
          backgroundColor: isDark ? '#3f3f46' : '#e2e8f0',
          marginVertical: 8,
          marginBottom: 20,
        }}
      />

      {/* Chunk Presets */}
      <View style={{ marginBottom: 20 }}>
        <Text style={[styles.label, { color: labelColor, marginBottom: 12 }]}>
          快速配置 (Presets)
        </Text>
        <View style={{ flexDirection: 'row', gap: 8 }}>
          <TouchableOpacity
            onPress={() => handlePresetSelect(800, 100)}
            style={[
              styles.presetBtn,
              {
                backgroundColor: isDark ? '#1e293b' : '#f1f5f9',
                borderColor:
                  activePreset === 'balanced' ? '#10b981' : isDark ? '#334155' : '#e2e8f0',
              },
            ]}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 4 }}>
              <Sparkles size={14} color={activePreset === 'balanced' ? '#10b981' : subTextColor} />
              <Text
                style={[
                  styles.presetTitle,
                  { color: activePreset === 'balanced' ? '#10b981' : textColor, marginLeft: 6 },
                ]}
              >
                {t.rag.presetBalanced}
              </Text>
            </View>
            <Text style={{ fontSize: 10, color: descColor }}>800 / 100</Text>
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => handlePresetSelect(1500, 300)}
            style={[
              styles.presetBtn,
              {
                backgroundColor: isDark ? '#1e293b' : '#f1f5f9',
                borderColor:
                  activePreset === 'writing' ? '#f59e0b' : isDark ? '#334155' : '#e2e8f0',
              },
            ]}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 4 }}>
              <BookOpen size={14} color={activePreset === 'writing' ? '#f59e0b' : subTextColor} />
              <Text
                style={[
                  styles.presetTitle,
                  { color: activePreset === 'writing' ? '#f59e0b' : textColor, marginLeft: 6 },
                ]}
              >
                {t.rag.presetWriting}
              </Text>
            </View>
            <Text style={{ fontSize: 10, color: descColor }}>1500 / 300</Text>
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => handlePresetSelect(1000, 200)}
            style={[
              styles.presetBtn,
              {
                backgroundColor: isDark ? '#1e293b' : '#f1f5f9',
                borderColor: activePreset === 'code' ? '#6366f1' : isDark ? '#334155' : '#e2e8f0',
              },
            ]}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 4 }}>
              <Code2 size={14} color={activePreset === 'code' ? '#6366f1' : subTextColor} />
              <Text
                style={[
                  styles.presetTitle,
                  { color: activePreset === 'code' ? '#6366f1' : textColor, marginLeft: 6 },
                ]}
              >
                {t.rag.presetCode}
              </Text>
            </View>
            <Text style={{ fontSize: 10, color: descColor }}>1000 / 200</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Chunk Size */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.chunkSize}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.docChunkSize} chars
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.chunkSizeDesc}</Text>
        <Slider
          value={effectiveConfig.docChunkSize}
          onValueChange={(val: number) => updateField('docChunkSize', Math.floor(val))}
          minimumValue={200}
          maximumValue={2000}
          step={50}
          minimumTrackTintColor="#10b981"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#10b981"
        />
      </View>

      {/* Chunk Overlap */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.chunkOverlap}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.chunkOverlap} chars
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.chunkOverlapDesc}</Text>
        <Slider
          value={effectiveConfig.chunkOverlap}
          onValueChange={(val: number) => updateField('chunkOverlap', Math.floor(val))}
          minimumValue={0}
          maximumValue={500}
          step={10}
          minimumTrackTintColor="#f59e0b"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#f59e0b"
        />
      </View>

      {/* Summary Threshold */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.summaryThreshold}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.summaryThreshold} 条消息
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.summaryThresholdDesc}</Text>
        <Slider
          value={effectiveConfig.summaryThreshold}
          onValueChange={(val: number) => updateField('summaryThreshold', Math.floor(val))}
          minimumValue={5}
          maximumValue={50}
          step={1}
          minimumTrackTintColor="#ef4444"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#ef4444"
        />
      </View>

      <View
        style={{
          height: 1,
          backgroundColor: isDark ? '#3f3f46' : '#e2e8f0',
          marginVertical: 8,
          marginBottom: 20,
        }}
      />

      {/* Retrieval Configuration */}
      <Text style={[styles.sectionTitle, { color: textColor, marginBottom: 20 }]}>
        检索配置 (Retrieval)
      </Text>

      {/* Memory Limit */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.memoryLimit}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.memoryLimit} 条
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.memoryLimitDesc}</Text>
        <Slider
          value={effectiveConfig.memoryLimit}
          onValueChange={(val: number) => updateField('memoryLimit', Math.floor(val))}
          minimumValue={1}
          maximumValue={20}
          step={1}
          minimumTrackTintColor="#8b5cf6"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#8b5cf6"
        />
      </View>

      {/* Memory Threshold */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.memoryThreshold}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.memoryThreshold.toFixed(2)}
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.memoryThresholdDesc}</Text>
        <Slider
          value={effectiveConfig.memoryThreshold}
          onValueChange={(val: number) =>
            updateField('memoryThreshold', parseFloat(val.toFixed(2)))
          }
          minimumValue={0.01}
          maximumValue={0.99}
          step={0.01}
          minimumTrackTintColor="#8b5cf6"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#8b5cf6"
        />
      </View>

      {/* Doc Limit */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.docLimit}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>{effectiveConfig.docLimit} 条</Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.docLimitDesc}</Text>
        <Slider
          value={effectiveConfig.docLimit}
          onValueChange={(val: number) => updateField('docLimit', Math.floor(val))}
          minimumValue={1}
          maximumValue={20}
          step={1}
          minimumTrackTintColor="#06b6d4"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#06b6d4"
        />
      </View>

      {/* Doc Threshold */}
      <View style={styles.fieldContainer}>
        <View style={styles.labelRow}>
          <Text style={[styles.label, { color: labelColor }]}>{t.rag.docThreshold}</Text>
          <Text style={[styles.value, { color: subTextColor }]}>
            {effectiveConfig.docThreshold.toFixed(2)}
          </Text>
        </View>
        <Text style={[styles.description, { color: descColor }]}>{t.rag.docThresholdDesc}</Text>
        <Slider
          value={effectiveConfig.docThreshold}
          onValueChange={(val: number) => updateField('docThreshold', parseFloat(val.toFixed(2)))}
          minimumValue={0.01}
          maximumValue={0.99}
          step={0.01}
          minimumTrackTintColor="#06b6d4"
          maximumTrackTintColor={isDark ? '#334155' : '#cbd5e1'}
          thumbTintColor="#06b6d4"
        />
      </View>

      <View
        style={{
          height: 1,
          backgroundColor: isDark ? '#3f3f46' : '#e2e8f0',
          marginVertical: 8,
          marginBottom: 20,
        }}
      />

      {/* Summary Prompt */}
      <View style={styles.fieldContainer}>
        <Text style={[styles.label, { color: labelColor }]}>{t.rag.summaryPrompt}</Text>
        <Text style={[styles.description, { color: descColor, marginBottom: 8 }]}>
          {t.rag.summaryPromptDesc}
        </Text>
        <TextInput
          style={[
            styles.promptInput,
            { backgroundColor: inputBg, borderColor: inputBorder, color: textColor },
          ]}
          multiline
          numberOfLines={4}
          value={effectiveConfig.summaryPrompt}
          onChangeText={(text) => updateField('summaryPrompt', text)}
          placeholder={t.rag.promptPlaceholder}
          placeholderTextColor={descColor}
          textAlignVertical="top"
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 20,
    marginTop: 4,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  resetButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 16,
  },
  resetText: {
    fontSize: 11,
    marginLeft: 4,
    fontWeight: '600',
  },
  fieldContainer: {
    marginBottom: 24,
  },
  labelRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'baseline',
    marginBottom: 8,
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
  },
  value: {
    fontSize: 13,
    fontFamily: 'monospace',
    fontWeight: '500',
  },
  description: {
    fontSize: 12,
    lineHeight: 18,
    marginBottom: 12,
  },
  promptInput: {
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
    minHeight: 100,
    fontSize: 14,
    lineHeight: 20,
  },
  presetBtn: {
    flex: 1,
    padding: 12,
    borderRadius: 12,
    borderWidth: 2,
    alignItems: 'center',
  },
  presetTitle: {
    fontSize: 13,
    fontWeight: '700',
  },
});
