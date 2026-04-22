// expo-router mock
const useRouter = () => ({
  push: () => {},
  replace: () => {},
  back: () => {},
  dismiss: () => {},
  canDismiss: () => true,
  setParams: () => {},
  navigate: () => {},
});

const useLocalSearchParams = () => ({});
const useGlobalSearchParams = () => ({});
const useSearchParams = useLocalSearchParams;

const Stack = { Screen: () => null };
const Tabs = { Screen: () => null };
const Drawer = { Screen: () => null };
const Redirect = () => null;
const Link = () => null;
const router = {
  push: () => {},
  replace: () => {},
  back: () => {},
  dismiss: () => {},
  canDismiss: () => true,
  setParams: () => {},
  navigate: () => {},
};

module.exports = {
  useRouter,
  useLocalSearchParams,
  useGlobalSearchParams,
  useSearchParams,
  Stack,
  Tabs,
  Drawer,
  Redirect,
  Link,
  router,
};
