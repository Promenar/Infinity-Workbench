declare module 'expo-drag-drop-content-view' {
  import { ViewProps } from 'react-native';
  export interface DragDropContentViewProps extends ViewProps {
    onDrop?: (event: { assets: any[] }) => void;
  }
  export class DragDropContentView extends React.Component<DragDropContentViewProps> {}
}

declare module 'react-native-safe-area-context' {
  export interface EdgeInsets {
    top: number;
    right: number;
    bottom: number;
    left: number;
  }

  export interface SafeAreaViewProps extends import('react-native').ViewProps {
    edges?: Array<'top' | 'right' | 'bottom' | 'left'>;
    mode?: 'padding' | 'margin';
  }

  export function useSafeAreaInsets(): EdgeInsets;
  export function useSafeAreaFrame(): { x: number; y: number; width: number; height: number };
  export const SafeAreaProvider: React.FC<{ children?: React.ReactNode }>;
  export const SafeAreaView: React.FC<SafeAreaViewProps>;
  export const SafeAreaConsumer: any;
}
