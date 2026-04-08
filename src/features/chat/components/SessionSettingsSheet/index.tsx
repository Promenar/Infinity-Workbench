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
}

export const SessionSettingsSheet: React.FC<SessionSettingsSheetProps> = ({
  visible,
  onClose,
  sessionId,
}) => {
  const [activeTab, setActiveTab] = useState('model');

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
      <TabBar activeTab={activeTab} onTabChange={setActiveTab} />
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
