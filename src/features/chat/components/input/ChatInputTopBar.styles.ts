/**
 * ChatInputTopBar Styles
 * 独立的TopBar样式定义
 */
import { StyleSheet, Platform } from 'react-native';

export const getTopBarStyles = (isDark: boolean, colors: any) => StyleSheet.create({
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
        backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
        marginRight: 6,
    },
    tokenBar: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 10,
        backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)',
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
    spacer: {
        flex: 1,
    },
    // 工作区按钮样式
    workspaceButton: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 8,
        paddingVertical: 4,
        borderRadius: 10,
        backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
        marginLeft: 6,
    },
    workspaceButtonText: {
        fontSize: 9,
        fontWeight: '600',
        marginLeft: 4,
        color: isDark ? '#a1a1aa' : '#71717a',
    },
    // 上下文进度条样式
    contextProgressBar: {
        width: 32,
        height: 3,
        borderRadius: 1.5,
        backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.08)',
        marginLeft: 4,
        overflow: 'hidden',
    },
    contextProgressFill: {
        height: '100%',
        borderRadius: 1.5,
    },
});
