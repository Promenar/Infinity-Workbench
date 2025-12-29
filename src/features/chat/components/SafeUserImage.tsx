import React, { useState } from 'react';
import { View, TouchableOpacity, Image } from 'react-native';
import { AlertCircle } from 'lucide-react-native';

export const SafeUserImage = ({ uri, onPress, isDark }: { uri: string, onPress: () => void, isDark: boolean }) => {
    const [error, setError] = useState(false);

    if (error) {
        return (
            <View style={{
                width: 100, height: 100, borderRadius: 8,
                backgroundColor: isDark ? '#3f3f46' : '#e4e4e7',
                justifyContent: 'center', alignItems: 'center'
            }}>
                <AlertCircle size={24} color={isDark ? '#71717a' : '#a1a1aa'} />
            </View>
        );
    }

    return (
        <TouchableOpacity onPress={onPress} activeOpacity={0.8}>
            <Image
                source={{ uri }}
                style={{
                    width: 100,
                    height: 100,
                    borderRadius: 8,
                    backgroundColor: isDark ? '#3f3f46' : '#e4e4e7'
                }}
                resizeMode="cover"
                onError={() => setError(true)}
            />
        </TouchableOpacity>
    );
};
