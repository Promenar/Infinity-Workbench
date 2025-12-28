import React from 'react';
import { View, ViewStyle } from 'react-native';
import { BrandIcon } from './BrandIcons';
import { Cpu, Sparkles } from 'lucide-react-native';

interface ModelIconRendererProps {
    icon?: string;
    size?: number;
    color?: string; // fallback color for generic icons
    style?: ViewStyle;
}

export const ModelIconRenderer: React.FC<ModelIconRendererProps> = ({ icon, size = 24, color = '#6366f1', style }) => {

    // Normalize icon key
    const iconKey = icon?.toLowerCase();

    let IconComponent;

    switch (iconKey) {
        case 'openai':
            IconComponent = BrandIcon.OpenAI;
            break;
        case 'anthropic':
        case 'claude':
            IconComponent = BrandIcon.Anthropic;
            break;
        case 'google':
        case 'gemini':
            IconComponent = BrandIcon.Google;
            break;
        case 'deepseek':
            IconComponent = BrandIcon.DeepSeek;
            break;
        case 'zhipu':
        case 'glm':
        case 'chatglm':
            IconComponent = BrandIcon.Zhipu;
            break;
        case 'moonshot':
        case 'kimi':
            IconComponent = BrandIcon.Moonshot;
            break;
        case 'reasoning': // Generic reasoning icon
            IconComponent = BrandIcon.Attention;
            break;
        default:
            // Fallback for unknown brands
            return (
                <View style={style}>
                    <Sparkles size={size} color={color} />
                </View>
            );
    }

    return (
        <View style={style}>
            <IconComponent size={size} color={color} />
        </View>
    );
};
