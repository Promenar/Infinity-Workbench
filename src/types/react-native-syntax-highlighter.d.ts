declare module 'react-native-syntax-highlighter' {
    import React from 'react';
    import { TextProps, TextStyle, StyleProp } from 'react-native';

    interface SyntaxHighlighterProps extends TextProps {
        language?: string;
        style?: any;
        customStyle?: StyleProp<TextStyle>;
        children?: React.ReactNode;
        highlighter?: 'prism' | 'hljs';
        fontSize?: number;
        fontFamily?: string;
        CodeTag?: React.ComponentType<any>;
        PreTag?: React.ComponentType<any>;
    }

    export default class SyntaxHighlighter extends React.Component<SyntaxHighlighterProps> { }
}

declare module 'react-syntax-highlighter/dist/esm/styles/hljs';
