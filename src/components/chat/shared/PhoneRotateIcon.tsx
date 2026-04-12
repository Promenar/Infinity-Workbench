import React from 'react';
import Svg, { Path, Rect, G } from 'react-native-svg';

interface PhoneRotateIconProps {
    size: number;
    color: string;
}

/**
 * PhoneRotateIcon - 手机旋转图标
 * 从 EChartsRenderer 和 MermaidRenderer 中提取的共享组件
 */
export const PhoneRotateIcon: React.FC<PhoneRotateIconProps> = ({ size, color }) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
        <Path d="M3.5 12C3.5 7.30558 7.30558 3.5 12 3.5" stroke={color} strokeWidth="2" strokeLinecap="round" />
        <Path d="M20.5 12C20.5 16.6944 16.6944 20.5 12 20.5" stroke={color} strokeWidth="2" strokeLinecap="round" />
        <Path d="M12 3.5H15M12 3.5V6.5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        <Path d="M12 20.5H9M12 20.5V17.5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        <G transform="rotate(45, 12, 12)">
            <Rect x="8" y="5" width="8" height="14" rx="1.5" stroke={color} strokeWidth="2" />
            <Path d="M11 16H13" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
        </G>
    </Svg>
);
