const { withAppBuildGradle, withGradleProperties } = require('@expo/config-plugins');

/**
 * Expo Config Plugin to restrict Android build to ARM64 architecture only.
 * This reduces APK size by ~50% by excluding x86, x86_64, and armeabi-v7a.
 */
const withArm64Only = (config) => {
    // 1. 修改 gradle.properties 中的 reactNativeArchitectures
    config = withGradleProperties(config, (config) => {
        const props = config.modResults;
        
        // 移除现有的 reactNativeArchitectures 配置
        const existingIndex = props.findIndex(
            (item) => item.key === 'reactNativeArchitectures'
        );
        if (existingIndex !== -1) {
            props.splice(existingIndex, 1);
        }
        
        // 添加仅 ARM64 的配置
        props.push({
            type: 'property',
            key: 'reactNativeArchitectures',
            value: 'arm64-v8a',
        });
        
        return config;
    });

    // 2. 修改 app/build.gradle 添加 ndk abiFilters
    config = withAppBuildGradle(config, (config) => {
        let buildGradle = config.modResults.contents;

        // 检查是否已有 ndk abiFilters 配置
        if (!/abiFilters\s*'arm64-v8a'/.test(buildGradle)) {
            // 在 defaultConfig 块的 buildConfigField 后添加 ndk 配置
            // 匹配 defaultConfig { ... buildConfigField ... } 并在其后插入
            buildGradle = buildGradle.replace(
                /(buildConfigField\s+"String",\s+"REACT_NATIVE_RELEASE_LEVEL"[^\n]*\n)(\s*})/,
                '$1\n        // 仅编译 ARM64 架构以减小 APK 体积\n        ndk {\n            abiFilters \'arm64-v8a\'\n        }\n    }'
            );
        }

        config.modResults.contents = buildGradle;
        return config;
    });

    return config;
};

module.exports = withArm64Only;
