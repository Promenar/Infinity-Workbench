declare module 'expo-drag-drop-content-view' {
  import { ViewProps } from 'react-native';
  export interface DragDropContentViewProps extends ViewProps {
    onDrop?: (event: { assets: any[] }) => void;
  }
  export class DragDropContentView extends React.Component<DragDropContentViewProps> {}
}
