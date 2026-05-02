import { NativeModules } from 'react-native';

const { NexaraBridge } = NativeModules;

export interface NativeAgent {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  model: string;
  icon: string;
  color: string;
}

export interface NativeSession {
  id: string;
  agentId: string;
  title: string;
  lastMessage: string;
  lastUpdatedAt: number;
}

export const syncAgentsToNative = (agents: any[]) => {
  if (NexaraBridge?.updateAgents) {
    NexaraBridge.updateAgents(JSON.stringify(agents));
  }
};

export const syncSessionsToNative = (sessions: any[]) => {
  if (NexaraBridge?.updateSessions) {
    NexaraBridge.updateSessions(JSON.stringify(sessions));
  }
};

export const openNativeChat = () => {
  if (NexaraBridge?.openNativeChat) {
    NexaraBridge.openNativeChat();
  }
};

export const setCurrentSessionNative = (sessionId: string | null) => {
  if (NexaraBridge?.setCurrentSession) {
    NexaraBridge.setCurrentSession(sessionId);
  }
};

export default NexaraBridge;
