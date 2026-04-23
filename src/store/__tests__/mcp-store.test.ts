import { useMcpStore } from '../mcp-store';

jest.mock('@react-native-async-storage/async-storage', () => require('../../../scripts/mocks/async-storage'));
jest.mock('immer', () => ({
  produce: (...args: any[]) => {
    // 签名1: produce(base, recipe) => result
    if (args.length === 2) {
      const [base, recipe] = args;
      recipe(base);
      return base;
    }
    // 签名2: produce(recipe) => (base) => result  (curried)
    const [recipe] = args;
    return (base: any) => {
      recipe(base);
      return base;
    };
  },
}));

const mockServer = {
  id: 'test-1', name: 'Test Server', url: 'http://localhost:3000',
  type: 'sse' as const, enabled: true, defaultIncluded: true,
};

describe('useMcpStore', () => {
  beforeEach(() => {
    useMcpStore.setState({ servers: [] });
  });

  it('初始状态 servers 为空数组', () => {
    expect(useMcpStore.getState().servers).toEqual([]);
  });

  it('addServer 添加服务器', () => {
    useMcpStore.getState().addServer(mockServer);
    const servers = useMcpStore.getState().servers;
    expect(servers).toHaveLength(1);
    expect(servers[0].id).toBe('test-1');
    expect(servers[0].status).toBe('disconnected');
  });

  it('addServer 不添加重复 ID', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().addServer(mockServer);
    expect(useMcpStore.getState().servers).toHaveLength(1);
  });

  it('updateServer 更新服务器属性', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().updateServer('test-1', { name: 'Updated', enabled: false });
    const server = useMcpStore.getState().servers[0];
    expect(server.name).toBe('Updated');
    expect(server.enabled).toBe(false);
  });

  it('updateServer 对不存在的 ID 无操作', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().updateServer('nonexistent', { name: 'X' });
    expect(useMcpStore.getState().servers[0].name).toBe('Test Server');
  });

  it('removeServer 删除服务器', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().removeServer('test-1');
    expect(useMcpStore.getState().servers).toHaveLength(0);
  });

  it('removeServer 对不存在的 ID 无副作用', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().removeServer('nonexistent');
    expect(useMcpStore.getState().servers).toHaveLength(1);
  });

  it('setServerStatus 更新状态', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().setServerStatus('test-1', 'connected');
    expect(useMcpStore.getState().servers[0].status).toBe('connected');
  });

  it('setServerStatus 连接时清除错误', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().setServerStatus('test-1', 'error', 'connection failed');
    expect(useMcpStore.getState().servers[0].error).toBe('connection failed');
    useMcpStore.getState().setServerStatus('test-1', 'connected');
    expect(useMcpStore.getState().servers[0].error).toBeUndefined();
  });

  it('setServerStatus 设置错误信息', () => {
    useMcpStore.getState().addServer(mockServer);
    useMcpStore.getState().setServerStatus('test-1', 'error', 'timeout');
    expect(useMcpStore.getState().servers[0].status).toBe('error');
    expect(useMcpStore.getState().servers[0].error).toBe('timeout');
  });
});
