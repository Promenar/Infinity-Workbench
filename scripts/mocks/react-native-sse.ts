// react-native-sse mock
class NativeEventSource {
  constructor(_url: string) {
    // no-op
  }
  addEventListener(_event: string, _handler: Function) {
    return { remove: () => {} };
  }
  removeAllListeners() {
    // no-op
  }
  removeListener(_handler: Function) {
    // no-op
  }
}

class EventSource {
  constructor(_url: string, _options?: any) {
    // no-op
  }
  addEventListener(_event: string, _handler: Function) {
    return { remove: () => {} };
  }
  removeAllListeners() {
    // no-op
  }
  removeListener(_handler: Function) {
    // no-op
  }
  close() {
    // no-op
  }
}

module.exports = {
  NativeEventSource,
  EventSource,
};
