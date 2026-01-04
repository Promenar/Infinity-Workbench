import React from 'react';
import { Modal, View, TouchableOpacity, StyleSheet, Animated } from 'react-native';
import { Typography } from '../../../components/ui/Typography';
import { Copy, Trash2, X } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { useTheme } from '../../../theme/ThemeProvider';

interface MessageContextMenuProps {
  visible: boolean;
  onClose: () => void;
  onCopy: () => void;
  onDelete: () => void;
  isUserMessage: boolean;
}

export const MessageContextMenu: React.FC<MessageContextMenuProps> = ({
  visible,
  onClose,
  onCopy,
  onDelete,
  isUserMessage,
}) => {
  const { isDark } = useTheme();
  const slideAnim = React.useRef(new Animated.Value(300)).current;

  // We add local state to track visibility for animation purposes
  // This allows the close animation to finish before the modal unmounts
  const [isVisible, setIsVisible] = React.useState(visible);

  React.useEffect(() => {
    if (visible) {
      setIsVisible(true);
      Animated.spring(slideAnim, {
        toValue: 0,
        damping: 20,
        mass: 1,
        stiffness: 100,
        useNativeDriver: true,
      }).start();
    } else {
      // Animate out
      Animated.timing(slideAnim, {
        toValue: 300,
        duration: 200,
        useNativeDriver: true,
      }).start(() => {
        setIsVisible(false);
        if (onClose) onClose();
      });
    }
  }, [visible]);

  if (!isVisible && !visible) return null;

  const handleAction = (action: () => void, feedbackType = Haptics.ImpactFeedbackStyle.Light) => {
    setTimeout(() => {
      Haptics.impactAsync(feedbackType);
      action();
      onClose(); // This triggers the useEffect 'else' block for exit animation
    }, 0);
  };

  return (
    <Modal visible={isVisible} transparent animationType="fade" onRequestClose={onClose}>
      <TouchableOpacity style={styles.backdrop} activeOpacity={1} onPress={onClose}>
        <Animated.View
          style={[
            styles.menu,
            {
              backgroundColor: isDark ? '#1f1f22' : '#ffffff',
              transform: [{ translateY: slideAnim }],
            },
          ]}
        >
          {/* Header/Handle */}
          <View style={styles.handleContainer}>
            <View style={[styles.handle, { backgroundColor: isDark ? '#3f3f46' : '#e4e4e7' }]} />
          </View>

          <TouchableOpacity style={styles.menuItem} onPress={() => handleAction(onCopy)}>
            <View
              style={[styles.iconContainer, { backgroundColor: isDark ? '#3f3f46' : '#f4f4f5' }]}
            >
              <Copy size={22} color={isDark ? '#fff' : '#000'} />
            </View>
            <Typography className="ml-4 text-[17px] font-semibold">复制内容</Typography>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.menuItem}
            onPress={() => handleAction(onDelete, Haptics.ImpactFeedbackStyle.Medium)}
          >
            <View style={[styles.iconContainer, { backgroundColor: '#fee2e2' }]}>
              <Trash2 size={22} color="#ef4444" />
            </View>
            <Typography className="ml-4 text-[17px] font-semibold text-red-500">
              删除消息
            </Typography>
          </TouchableOpacity>

          {/* Safe area padding at bottom */}
          <View style={{ height: 40 }} />
        </Animated.View>
      </TouchableOpacity>
    </Modal>
  );
};

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.4)',
    justifyContent: 'flex-end',
  },
  menu: {
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    paddingHorizontal: 20,
    paddingTop: 10,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.1,
    shadowRadius: 10,
    elevation: 20,
  },
  handleContainer: {
    width: '100%',
    alignItems: 'center',
    paddingVertical: 12,
    marginBottom: 8,
  },
  handle: {
    width: 40,
    height: 4,
    borderRadius: 2,
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 16,
  },
  iconContainer: {
    width: 44,
    height: 44,
    borderRadius: 22,
    justifyContent: 'center',
    alignItems: 'center',
  },
});
