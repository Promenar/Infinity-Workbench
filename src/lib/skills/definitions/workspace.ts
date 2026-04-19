import { z } from 'zod';
import { Skill } from '../../../types/skills';
import * as FileSystem from 'expo-file-system/legacy';
import { useChatStore } from '../../../store/chat-store';

const WORKSPACE_BASE_PATH = 'agent_sandbox';

export interface WorkspaceInfo {
  path: string;
  fullPath: string;
  name: string;
  exists: boolean;
  hasTasks: boolean;
  hasArtifacts: boolean;
}

const checkWorkspaceContents = async (fullPath: string): Promise<{ hasTasks: boolean; hasArtifacts: boolean }> => {
  let hasTasks = false;
  let hasArtifacts = false;

  try {
    const tasksPath = `${fullPath}/.tasks/active`;
    const tasksInfo = await FileSystem.getInfoAsync(tasksPath);
    if (tasksInfo.exists) {
      const files = await FileSystem.readDirectoryAsync(tasksPath);
      hasTasks = files.some((f: string) => f.endsWith('.md'));
    }
  } catch {}

  try {
    const artifactsPath = `${fullPath}/.artifacts`;
    const artifactsInfo = await FileSystem.getInfoAsync(artifactsPath);
    if (artifactsInfo.exists) {
      const subdirs = await FileSystem.readDirectoryAsync(artifactsPath);
      for (const subdir of subdirs) {
        const subdirPath = `${artifactsPath}/${subdir}`;
        const subdirInfo = await FileSystem.getInfoAsync(subdirPath);
        if (subdirInfo.exists && (subdirInfo as any).isDirectory) {
          const files = await FileSystem.readDirectoryAsync(subdirPath);
          if (files.length > 0) {
            hasArtifacts = true;
            break;
          }
        }
      }
    }
  } catch {}

  return { hasTasks, hasArtifacts };
};

export const ListWorkspacesSkill: Skill = {
  id: 'list_workspaces',
  name: 'List Workspaces',
  description: `List all available workspace directories.

Returns a list of workspaces with their status.
**Note:** To bind a workspace, use the UI in the workspace panel.`,
  schema: z.object({
    includeContents: z.boolean().optional().describe('Include task/artifact counts (default: false)'),
  }),
  execute: async (params, context) => {
    try {
      const basePath = `${FileSystem.documentDirectory}${WORKSPACE_BASE_PATH}`;
      const baseInfo = await FileSystem.getInfoAsync(basePath);

      if (!baseInfo.exists) {
        return {
          id: `list_workspaces_${Date.now()}`,
          content: 'No workspaces found. Use the workspace panel to create one.',
          status: 'success',
          data: { workspaces: [], count: 0 },
        };
      }

      const entries = await FileSystem.readDirectoryAsync(basePath);
      const workspaces: WorkspaceInfo[] = [];

      for (const entry of entries) {
        if (entry.startsWith('.')) continue;

        const entryPath = `${basePath}/${entry}`;
        const info = await FileSystem.getInfoAsync(entryPath);

        if (info.exists && (info as any).isDirectory) {
          const { hasTasks, hasArtifacts } = params.includeContents
            ? await checkWorkspaceContents(entryPath)
            : { hasTasks: false, hasArtifacts: false };

          workspaces.push({
            path: entry,
            fullPath: entryPath,
            name: entry,
            exists: true,
            hasTasks,
            hasArtifacts,
          });
        }
      }

      if (workspaces.length === 0) {
        return {
          id: `list_workspaces_${Date.now()}`,
          content: 'No workspaces found. Use the workspace panel to create one.',
          status: 'success',
          data: { workspaces: [], count: 0 },
        };
      }

      const summary = workspaces.map(w => {
        const contents = params.includeContents
          ? ` [Tasks: ${w.hasTasks ? '✅' : '⬜'}, Artifacts: ${w.hasArtifacts ? '✅' : '⬜'}]`
          : '';
        return `- **${w.name}**${contents}`;
      }).join('\n');

      return {
        id: `list_workspaces_${Date.now()}`,
        content: `Found ${workspaces.length} workspace(s):\n\n${summary}`,
        status: 'success',
        data: { workspaces, count: workspaces.length },
      };
    } catch (e: any) {
      return {
        id: `list_workspaces_err_${Date.now()}`,
        content: `Failed to list workspaces: ${e.message}`,
        status: 'error',
      };
    }
  },
};

export const GetWorkspaceStatusSkill: Skill = {
  id: 'get_workspace_status',
  name: 'Get Workspace Status',
  description: `Get the current workspace binding status for this session.

Returns the bound workspace path or indicates if using default.
**Note:** To change workspace, use the UI in the workspace panel.`,
  schema: z.object({}),
  execute: async (params, context) => {
    try {
      const sessionId = context?.sessionId;
      const session = sessionId
        ? useChatStore.getState().sessions.find(s => s.id === sessionId)
        : null;

      const workspacePath = session?.workspacePath || context?.workspacePath || 'workspace';
      const fullPath = `${FileSystem.documentDirectory}${WORKSPACE_BASE_PATH}/${workspacePath}`;

      const { hasTasks, hasArtifacts } = await checkWorkspaceContents(fullPath);

      return {
        id: `get_workspace_status_${Date.now()}`,
        content: `**Current Workspace:** ${workspacePath}\n\n**Contents:**\n- Tasks: ${hasTasks ? '✅ Present' : '⬜ Empty'}\n- Artifacts: ${hasArtifacts ? '✅ Present' : '⬜ Empty'}`,
        status: 'success',
        data: {
          path: workspacePath,
          fullPath,
          hasTasks,
          hasArtifacts,
          isDefault: workspacePath === 'workspace',
        },
      };
    } catch (e: any) {
      return {
        id: `get_workspace_status_err_${Date.now()}`,
        content: `Failed to get workspace status: ${e.message}`,
        status: 'error',
      };
    }
  },
};
