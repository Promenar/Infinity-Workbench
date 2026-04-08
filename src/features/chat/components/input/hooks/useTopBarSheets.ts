/**
 * useTopBarSheets Hook
 * 管理TopBar中Sheet的显示状态
 */
import { useState, useCallback } from 'react';

export interface TopBarSheetsState {
    showSettingsSheet: boolean;
    showWorkspaceSheet: boolean;
}

export interface TopBarSheetsActions {
    openSettings: () => void;
    closeSettings: () => void;
    toggleSettings: () => void;
    openWorkspace: () => void;
    closeWorkspace: () => void;
    toggleWorkspace: () => void;
    closeAll: () => void;
}

export type UseTopBarSheetsReturn = TopBarSheetsState & TopBarSheetsActions;

/**
 * 管理ChatInputTopBar中两个Sheet的状态
 * - SessionSettingsSheet: 模型选择器、思考级别等设置
 * - WorkspaceSheet: 工作区文件、任务、artifacts
 */
export const useTopBarSheets = (): UseTopBarSheetsReturn => {
    const [showSettingsSheet, setShowSettingsSheet] = useState(false);
    const [showWorkspaceSheet, setShowWorkspaceSheet] = useState(false);

    const openSettings = useCallback(() => {
        setShowSettingsSheet(true);
        setShowWorkspaceSheet(false); // 关闭另一个sheet
    }, []);

    const closeSettings = useCallback(() => {
        setShowSettingsSheet(false);
    }, []);

    const toggleSettings = useCallback(() => {
        setShowSettingsSheet((prev) => {
            if (!prev) setShowWorkspaceSheet(false); // 打开时关闭另一个
            return !prev;
        });
    }, []);

    const openWorkspace = useCallback(() => {
        setShowWorkspaceSheet(true);
        setShowSettingsSheet(false); // 关闭另一个sheet
    }, []);

    const closeWorkspace = useCallback(() => {
        setShowWorkspaceSheet(false);
    }, []);

    const toggleWorkspace = useCallback(() => {
        setShowWorkspaceSheet((prev) => {
            if (!prev) setShowSettingsSheet(false); // 打开时关闭另一个
            return !prev;
        });
    }, []);

    const closeAll = useCallback(() => {
        setShowSettingsSheet(false);
        setShowWorkspaceSheet(false);
    }, []);

    return {
        showSettingsSheet,
        showWorkspaceSheet,
        openSettings,
        closeSettings,
        toggleSettings,
        openWorkspace,
        closeWorkspace,
        toggleWorkspace,
        closeAll,
    };
};
