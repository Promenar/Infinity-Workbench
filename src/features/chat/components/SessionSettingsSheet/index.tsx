import React, { useState } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';
import { useTheme } from '../../../../theme/ThemeProvider';
import { GlassBottomSheet } from '../../../../components/ui/GlassBottomSheet';
import { TabBar } from './TabBar';
import { ModelSelectorPanel } from './ModelSelectorPanel';
import { ThinkingLevelPanel } from './ThinkingLevelPanel';
import { StatsPanel } from './StatsPanel';
import { ToolsPanel } from './ToolsPanel';

interface SessionSettingsSheetProps {
  visible: boolean;
  onClose: () => void;
  sessionId: string;
  initialTab?: 'model' | 'thinking' | 'stats' | 'tools';
}

export const SessionSettingsSheet: React.FC<SessionSettingsSheetProps> = ({
  visible,
  onClose,
  sessionId,
  initialTab = 'model',
}) => {
  const [activeTab, setActiveTab] = React.useState<'model' | 'thinking' | 'stats' | 'tools'>(initialTab);

  // Force tab when opening
  React.useEffect(() => {
    if (visible) {
      setActiveTab(initialTab);
    }
  }, [visible, initialTab]);

  const renderContent = () => {
    switch (activeTab) {
      case 'model':
        return <ModelSelectorPanel sessionId={sessionId} />;
      case 'thinking':
        return <ThinkingLevelPanel sessionId={sessionId} />;
      case 'stats':
        return <StatsPanel sessionId={sessionId} />;
      case 'tools':
        return <ToolsPanel sessionId={sessionId} />;
      default:
        return null;
    }
  };

  return (
    <GlassBottomSheet visible={visible} onClose={onClose} title="会话设置" height="70%">
      <TabBar activeTab={activeTab} onTabChange={(tab) => setActiveTab(tab as any)} />
      <View style={styles.content}>
        <Animated.View
          key={activeTab}
          entering={FadeIn.duration(200)}
          exiting={FadeOut.duration(150)}
          style={styles.contentInner}
        >
          {renderContent()}
        </Animated.View>
      </View>
    </GlassBottomSheet>
  );
};

const styles = StyleSheet.create({
  content: {
    flex: 1,
    overflow: 'hidden',
  },
  contentInner: {
    flex: 1,
  },
});

export { TabBar } from './TabBar';
export { ModelSelectorPanel } from './ModelSelectorPanel';
export { ThinkingLevelPanel } from './ThinkingLevelPanel';
export { StatsPanel } from './StatsPanel';
export { ToolsPanel } from './ToolsPanel';
