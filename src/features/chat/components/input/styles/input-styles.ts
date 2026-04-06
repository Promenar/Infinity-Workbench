import { StyleSheet, Platform } from 'react-native';

export const getInputStyles = (isDark: boolean, colors: any) => StyleSheet.create({
  outerContainer: {
    marginHorizontal: 20,
    ...Platform.select({
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 6 },
        shadowOpacity: 0.12,
        shadowRadius: 12,
      },
    }),
  },
  blurContainer: {
    borderRadius: 24,
    overflow: 'hidden',
    ...Platform.select({
      android: { elevation: 10 },
    }),
  },
  overlay: {
    position: 'absolute',
    top: 0, left: 0, right: 0, bottom: 0,
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 12,
    paddingTop: 6,
    marginBottom: 2,
  },
  modelBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.03)',
    marginRight: 6,
  },
  tokenBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.02)',
  },
  topBarText: {
    fontSize: 9,
    fontWeight: '900',
    marginLeft: 4,
    color: '#94a3b8',
    textTransform: 'uppercase',
  },
  modeSelectors: {
    paddingRight: 12,
    flexDirection: 'row',
    alignItems: 'center',
  },
  contentContainer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  iconButton: {
    width: 36, height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  inputWrapper: {
    minHeight: 36,
    justifyContent: 'center',
    paddingHorizontal: 8,
    borderWidth: 1.5,
    borderRadius: 12,
  },
  input: {
    fontSize: 16,
    maxHeight: 240,
    paddingVertical: 6,
    textAlignVertical: 'top',
    backgroundColor: 'transparent',
  },
  previewContainer: {
    marginBottom: 8,
    height: 64,
  },
  previewItem: {
    width: 60, height: 60,
    marginRight: 8,
    borderRadius: 8,
    overflow: 'hidden',
    position: 'relative',
  },
  previewImage: {
    width: '100%', height: '100%',
  },
  filePreviewItem: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  filePreviewText: {
    fontSize: 9,
    marginTop: 4,
    width: '90%',
    textAlign: 'center',
  },
  removeButton: {
    position: 'absolute',
    top: 2, right: 2,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 10,
    width: 18, height: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonContainer: {
    width: 40, height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 2,
  },
  svgContainer: {
    position: 'absolute',
    top: 0, left: 0, width: 40, height: 40,
  },
  sendButton: {
    width: 32, height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1,
  },
  attachmentMenu: {
    position: 'absolute',
    bottom: 60, left: 12,
    width: 160,
    borderRadius: 16,
    borderWidth: 1,
    overflow: 'hidden',
    ...Platform.select({
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.15,
        shadowRadius: 12,
      },
      android: { elevation: 8 },
    }),
  },
  attachmentMenuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  divider: {
    height: 1,
    backgroundColor: 'rgba(0,0,0,0.05)',
  },
  editBanner: {
    position: 'absolute',
    top: -12, left: 0, right: 0,
    zIndex: 100,
    alignItems: 'center',
  },
  editBadge: {
    backgroundColor: 'rgba(239, 68, 68, 0.95)',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 10,
  },
  editBadgeText: {
    color: 'white',
    fontSize: 10,
    fontWeight: '600',
  },
  editShadow: {
    shadowColor: '#ef4444',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.5,
    shadowRadius: 12,
    elevation: 8,
  },
});
