import React from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography } from '../ui';

interface TagCapsuleProps {
    name: string;
    color: string;
    onPress?: () => void;
    onLongPress?: () => void;
    size?: 'sm' | 'md';
}

export const TagCapsule: React.FC<TagCapsuleProps> = ({ name, color, onPress, onLongPress, size = 'sm' }) => {
    return (
        <TouchableOpacity
            onPress={onPress}
            onLongPress={onLongPress}
            activeOpacity={0.7}
            style={{
                backgroundColor: `${color}20`, // 20 alpha
                borderColor: `${color}50`,
                borderWidth: 1,
                borderRadius: 12,
                paddingHorizontal: size === 'sm' ? 8 : 12,
                paddingVertical: size === 'sm' ? 2 : 4,
                marginRight: 6,
                marginBottom: 4,
                flexDirection: 'row',
                alignItems: 'center'
            }}
        >
            <Typography
                style={{
                    color: color,
                    fontSize: size === 'sm' ? 10 : 12,
                    fontWeight: '600'
                }}
            >
                {name}
            </Typography>
        </TouchableOpacity>
    );
};
