import React from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, ScrollView } from 'react-native';
import { BlurView } from 'expo-blur';
import { X, Clock, Database, ListOrdered, Zap, Search } from 'lucide-react-native';
import { RagMetadata } from '../../../types/chat';

interface RagDetailPanelProps {
  visible: boolean;
  onClose: () => void;
  metadata?: RagMetadata;
  isDark: boolean;
}

export const RagDetailPanel: React.FC<RagDetailPanelProps> = ({
  visible,
  onClose,
  metadata,
  isDark,
}) => {
  // Simple theme system
  const colors = {
    primary: isDark ? '#34d399' : '#059669',
    secondary: isDark ? '#60a5fa' : '#2563eb',
    surface: isDark ? '#18181b' : '#ffffff',
    surfaceVariant: isDark ? '#27272a' : '#f3f4f6',
    text: isDark ? '#f4f4f5' : '#18181b',
    textSecondary: isDark ? '#a1a1aa' : '#52525b',
    border: isDark ? '#3f3f46' : '#e4e4e7',
  };

  const borderRadius = { lg: 16, md: 12 };
  const spacing = { md: 16 };

  if (!metadata) return null;

  const renderMetricItem = (
    label: string,
    value: string | number,
    IconComponent: React.ElementType,
  ) => (
    <View style={[styles.metricItem, { backgroundColor: colors.surfaceVariant }]}>
      <IconComponent size={20} color={colors.primary} />
      <View style={styles.metricContent}>
        <Text style={[styles.metricValue, { color: colors.text }]}>{value}</Text>
        <Text style={[styles.metricLabel, { color: colors.textSecondary }]}>{label}</Text>
      </View>
    </View>
  );

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <BlurView intensity={20} style={StyleSheet.absoluteFill} tint="dark" />
        <TouchableOpacity style={styles.overlayTouch} onPress={onClose} activeOpacity={1} />

        <View
          style={[
            styles.container,
            {
              backgroundColor: colors.surface,
              borderRadius: borderRadius.lg,
              borderColor: colors.border,
              borderWidth: 1,
            },
          ]}
        >
          <View style={[styles.header, { borderBottomColor: colors.border }]}>
            <Text style={[styles.title, { color: colors.text }]}>RAG Gen. Details</Text>
            <TouchableOpacity onPress={onClose} style={styles.closeButton}>
              <X size={24} color={colors.textSecondary} />
            </TouchableOpacity>
          </View>

          <ScrollView style={styles.content}>
            {/* Core Metrics */}
            <View style={styles.metricsGrid}>
              {renderMetricItem(
                'Total Time',
                `${(metadata.totalTimeMs || (metadata.searchTimeMs + (metadata.rerankTimeMs || 0))).toFixed(0)}ms`,
                Clock,
              )}
              {renderMetricItem(
                'Max Similarity',
                `${((metadata.maxSimilarity || 0) * 100).toFixed(1)}%`,
                Zap,
              )}
              {renderMetricItem('Recall Count', metadata.recallCount, Database)}
              {renderMetricItem('Final Count', metadata.finalCount, ListOrdered)}
            </View>

            {metadata.rerankTimeMs !== undefined && (
              <View style={[styles.section, { marginTop: spacing.md }]}>
                <View style={[styles.metricItem, { backgroundColor: colors.surfaceVariant, minWidth: '100%' }]}>
                  <Zap size={20} color={colors.secondary} />
                  <View style={styles.metricContent}>
                    <Text style={[styles.metricValue, { color: colors.text }]}>{metadata.rerankTimeMs.toFixed(0)}ms</Text>
                    <Text style={[styles.metricLabel, { color: colors.textSecondary }]}>Rerank Time</Text>
                  </View>
                </View>
              </View>
            )}

            {/* Source Distribution */}
            {metadata.sourceDistribution && (
              <View style={[styles.section, { marginTop: spacing.md }]}>
                <Text style={[styles.sectionTitle, { color: colors.textSecondary }]}>
                  Source Distribution
                </Text>
                <View style={styles.distributionContainer}>
                  <View
                    style={[styles.distributionItem, { backgroundColor: colors.surfaceVariant }]}
                  >
                    <Text style={[styles.distributionValue, { color: colors.primary }]}>
                      {metadata.sourceDistribution.memory}
                    </Text>
                    <Text style={[styles.distributionLabel, { color: colors.textSecondary }]}>
                      Memory
                    </Text>
                  </View>
                  <View
                    style={[styles.distributionItem, { backgroundColor: colors.surfaceVariant }]}
                  >
                    <Text style={[styles.distributionValue, { color: colors.secondary }]}>
                      {metadata.sourceDistribution.documents}
                    </Text>
                    <Text style={[styles.distributionLabel, { color: colors.textSecondary }]}>
                      Documents
                    </Text>
                  </View>
                </View>
              </View>
            )}

            {/* Query Variants */}
            {metadata.queryVariants && metadata.queryVariants.length > 0 && (
              <View style={[styles.section, { marginTop: spacing.md }]}>
                <Text style={[styles.sectionTitle, { color: colors.textSecondary }]}>
                  Query Variants
                </Text>
                <View
                  style={[styles.variantsContainer, { backgroundColor: colors.surfaceVariant }]}
                >
                  {metadata.queryVariants.map((variant, index) => (
                    <View
                      key={index}
                      style={[
                        styles.variantItem,
                        {
                          borderBottomColor: colors.border,
                          borderBottomWidth:
                            index < (metadata.queryVariants?.length || 0) - 1 ? 1 : 0,
                        },
                      ]}
                    >
                      <Search size={14} color={colors.textSecondary} style={{ marginTop: 2 }} />
                      <Text style={[styles.variantText, { color: colors.text }]}>{variant}</Text>
                    </View>
                  ))}
                </View>
              </View>
            )}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  overlayTouch: {
    ...StyleSheet.absoluteFillObject,
  },
  container: {
    width: '100%',
    maxHeight: '80%',
    maxWidth: 500,
    elevation: 5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.25,
    shadowRadius: 10,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
  },
  closeButton: {
    padding: 4,
  },
  content: {
    padding: 16,
  },
  metricsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  metricItem: {
    flex: 1,
    minWidth: '45%',
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
    borderRadius: 12,
    gap: 12,
  },
  metricContent: {
    flex: 1,
  },
  metricValue: {
    fontSize: 18,
    fontWeight: '700',
  },
  metricLabel: {
    fontSize: 12,
    marginTop: 2,
  },
  section: {
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 8,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  distributionContainer: {
    flexDirection: 'row',
    gap: 12,
  },
  distributionItem: {
    flex: 1,
    alignItems: 'center',
    padding: 16,
    borderRadius: 12,
  },
  distributionValue: {
    fontSize: 24,
    fontWeight: '700',
  },
  distributionLabel: {
    fontSize: 12,
    marginTop: 4,
  },
  variantsContainer: {
    borderRadius: 12,
    overflow: 'hidden',
  },
  variantItem: {
    flexDirection: 'row',
    padding: 12,
    gap: 10,
  },
  variantText: {
    fontSize: 14,
    lineHeight: 20,
    flex: 1,
  },
});
