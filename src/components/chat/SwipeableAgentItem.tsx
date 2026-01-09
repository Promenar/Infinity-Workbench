import React from 'react';
import { View, Animated, StyleSheet, TouchableOpacity } from 'react-native';
import { Swipeable } from 'react-native-gesture-handler';
import { Agent } from '../../types/chat';
import { Typography } from '../ui/Typography';
import { Pin, Trash2, ChevronRight } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import { AgentAvatar } from './AgentAvatar';
import { useChatStore } from '../../store/chat-store';
import { Colors } from '../../theme/colors';
import { useTheme } from '../../theme/ThemeProvider';

interface SwipeableAgentItemProps {
  item: Agent;
  onPress: () => void;
  onPin: () => void;
  onDelete: () => void;
  isDark?: boolean;
}

export const SwipeableAgentItem = ({ item, onPress, onPin, onDelete }: SwipeableAgentItemProps) => {
  const swipeableRef = React.useRef<Swipeable>(null);
  const { isDark, colors } = useTheme();
  const themeColors = isDark ? Colors.dark : Colors.light;

  const isGenerating = useChatStore((state) => {
    return state.sessions.some((s) => s.agentId === item.id && !!state.activeRequests[s.id]);
  });

  const renderRightActions = (progress: any, dragX: any) => {
    const trans = dragX.interpolate({
      inputRange: [-80, 0],
      outputRange: [0, 80],
      extrapolate: 'clamp',
    });
    return (
      <View style={styles.rightActionContainer}>
        <Animated.View style={[styles.rightAction, { transform: [{ translateX: trans }] }]}>
          <TouchableOpacity
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
              swipeableRef.current?.close();
              onDelete();
            }}
            style={styles.deleteButton}
          >
            <Trash2 size={20} color="white" />
          </TouchableOpacity>
        </Animated.View>
      </View>
    );
  };

  const renderLeftActions = (progress: any, dragX: any) => {
    const trans = dragX.interpolate({
      inputRange: [0, 80],
      outputRange: [-80, 0],
      extrapolate: 'clamp',
    });
    return (
      <View style={styles.leftActionContainer}>
        <Animated.View style={[styles.leftAction, { transform: [{ translateX: trans }] }]}>
          <TouchableOpacity
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
              swipeableRef.current?.close();
              onPin();
            }}
            style={styles.pinButton}
          >
            <Pin size={20} color="white" fill={item.isPinned ? 'white' : 'none'} />
          </TouchableOpacity>
        </Animated.View>
      </View>
    );
  };

  return (
    <Swipeable
      ref={swipeableRef}
      renderRightActions={renderRightActions}
      renderLeftActions={renderLeftActions}
      containerStyle={styles.swipeContainer}
      useNativeAnimations
      friction={2}
      rightThreshold={40}
      leftThreshold={40}
    >
      <TouchableOpacity
        activeOpacity={0.7}
        style={{
          backgroundColor: isDark ? '#000' : '#fff', // Keep pure black/white for list items for contrast
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 16, // Reduced 24 -> 16
          paddingVertical: 13,   // Reduced 16 -> 13
          width: '100%',
        }}
        onPress={() => {
          swipeableRef.current?.close();
          onPress();
        }}
      >
        {/* Avatar Container */}
        <View className="relative mr-3">
          <AgentAvatar
            id={item.id}
            name={item.name}
            avatar={item.avatar}
            color={item.color}
            size={46} // Reduced 52 -> 46
          />
          {item.isPinned && (
            <View
              style={{
                position: 'absolute',
                bottom: -2,
                right: -2,
                backgroundColor: Colors.warning,
                padding: 3,
                borderRadius: 8,
                borderWidth: 2,
                borderColor: isDark ? '#000' : '#fff',
              }}
            >
              <Pin size={8} color="white" fill="white" />
            </View>
          )}
        </View>

        {/* Content */}
        <View className="flex-1 justify-center py-1">
          <View className="flex-row justify-between items-baseline mb-0.5 pr-1">
            <Typography
              variant="h3"
              style={{
                fontSize: 16.5, // Reduced 18 -> 16.5
                fontWeight: 'bold',
                lineHeight: 20, // Reduced 22 -> 20
                color: themeColors.textPrimary,
              }}
            >
              {item.name}
            </Typography>
            {item.isPreset && (
              <View
                style={{
                  backgroundColor: isDark ? colors.opacity10 : colors[50],
                  paddingHorizontal: 6,
                  paddingVertical: 2,
                  borderRadius: 6,
                  borderWidth: 1,
                  borderColor: isDark ? colors.opacity30 : colors[200],
                }}
              >
                <Typography
                  style={{
                    color: colors[500],
                    fontWeight: 'bold',
                    fontSize: 8,
                    textTransform: 'uppercase',
                    letterSpacing: -0.5,
                  }}
                >
                  PRESET
                </Typography>
              </View>
            )}
          </View>
          <Typography
            variant="body"
            style={{
              fontSize: 13,
              lineHeight: 20,
              color: isGenerating ? Colors.primary : themeColors.textSecondary,
              fontWeight: isGenerating ? '500' : '400',
              fontStyle: isGenerating ? 'italic' : 'normal',
            }}
            numberOfLines={1}
          >
            {isGenerating ? 'Thinking...' : item.description}
          </Typography>
        </View>

        <ChevronRight size={18} color={themeColors.textTertiary} style={{ marginLeft: 8 }} />
      </TouchableOpacity>
    </Swipeable>
  );
};

const styles = StyleSheet.create({
  swipeContainer: {
    width: '100%',
  },
  leftActionContainer: {
    width: 80,
    justifyContent: 'center',
    alignItems: 'center',
  },
  rightActionContainer: {
    width: 80,
    justifyContent: 'center',
    alignItems: 'center',
    alignSelf: 'flex-end',
  },
  leftAction: {
    flex: 1,
    width: '100%',
    justifyContent: 'center',
    alignItems: 'center',
  },
  rightAction: {
    flex: 1,
    width: '100%',
    justifyContent: 'center',
    alignItems: 'center',
  },
  deleteButton: {
    backgroundColor: Colors.error,
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: Colors.error,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 4.65,
    elevation: 8,
  },
  pinButton: {
    backgroundColor: Colors.warning,
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: Colors.warning,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 4.65,
    elevation: 8,
  },
});
