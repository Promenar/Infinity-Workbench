import { Redirect } from 'expo-router';
import { useSettingsStore } from '../src/store/settings-store';
import { View } from 'react-native';

export default function Index() {
  const { hasLaunched, _hasHydrated } = useSettingsStore();

  if (!_hasHydrated) {
    return <View />; // Wait for hydration
  }

  if (!hasLaunched) {
    return <Redirect href="/welcome" />;
  }

  return <Redirect href="/(tabs)/chat" />;
}
