// Jest teardown script
module.exports = async () => {
  // 清理全局状态
  if (global.__DEV__ !== undefined) {
    delete global.__DEV__;
  }

  // 清理可能的定时器
  // jest.useRealTimers() 在需要时由各个测试文件调用
};
