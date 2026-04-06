import { StyleSheet, Platform } from 'react-native';
import { Colors } from '../../../../../theme/colors';
import { Borders } from '../../../../../theme/glass';

export const getMessageStyles = (isDark: boolean, colors: any) => StyleSheet.create({
  // Message Row (Outer Container)
  rowContainer: {
    width: '100%',
    paddingHorizontal: 20,
    marginBottom: 24,
  },
  userRowContainer: {
    width: '100%',
    paddingHorizontal: 16,
    marginBottom: 16,
    alignItems: 'flex-end',
  },

  // Avatar
  avatarWrapper: {
    backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#ffffff',
    borderRadius: 9999,
    padding: 2,
    borderWidth: 1,
    borderColor: isDark ? Borders.glass.dark : Borders.glass.light,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 2,
    elevation: 1,
  },

  // Message Content Area
  contentContainer: {
    flex: 1,
  },
  userContentBubble: {
    backgroundColor: isDark ? colors[900] : colors[500],
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 18,
    borderBottomRightRadius: 4,
    maxWidth: '85%',
  },

  // Meta / Footer Info
  metaContainer: {
    marginTop: 4,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  
  // Attachments
  attachmentGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginTop: 8,
    gap: 8,
  },
  
  // Modals
  modalContent: {
    width: '100%',
    height: '80%',
    backgroundColor: isDark ? '#18181b' : '#ffffff',
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    padding: 24,
    paddingBottom: Platform.OS === 'ios' ? 40 : 24,
  }
});
