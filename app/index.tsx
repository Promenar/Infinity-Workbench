import { Redirect } from 'expo-router';
import { useSettingsStore } from '../src/store/settings-store';
import { View, Platform } from 'react-native';
import { useEffect } from 'react';
import { openNativeChat } from '../src/native/NexaraBridge';

export default function Index() {
  const { hasLaunched, _hasHydrated } = useSettingsStore();

  useEffect(() => {
    if (_hasHydrated && hasLaunched && Platform.OS === 'android') {
      console.log('[NativeBridge] Auto-redirecting to Native UI...');
      openNativeChat();
    }
  }, [_hasHydrated, hasLaunched]);

  if (!_hasHydrated) {
    return <View />; // Wait for hydration
  }

  if (!hasLaunched) {
    return <Redirect href="/welcome" />;
  }

  return <Redirect href="/(tabs)/chat" />;
}
