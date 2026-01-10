const { withAppBuildGradle } = require('@expo/config-plugins');

/**
 * Expo Config Plugin to inject debug specific configurations.
 * 修正逻辑：确保只在 buildTypes { ... } 块内的 debug { ... } 进行注入。
 */
const withAndroidDebugConfig = (config) => {
    return withAppBuildGradle(config, (config) => {
        let buildGradle = config.modResults.contents;

        // 检查是否已经应用，防止重复注入
        if (buildGradle.includes('applicationIdSuffix ".debug"')) {
            return config;
        }

        // 1. 提取 buildTypes 块的内容
        const buildTypesMatch = buildGradle.match(/buildTypes\s*{([\s\S]*?)\n\s*}/);
        if (buildTypesMatch) {
            let buildTypesContent = buildTypesMatch[1];

            // 2. 在 buildTypesContent 中查找 debug 块并注入属性
            if (buildTypesContent.includes('debug {')) {
                const updatedBuildTypesContent = buildTypesContent.replace(
                    /debug\s*{/,
                    `debug {
            applicationIdSuffix ".debug"
            resValue "string", "app_name", "Nexara (Dev)"`
                );

                // 3. 写回原文件内容
                buildGradle = buildGradle.replace(buildTypesContent, updatedBuildTypesContent);
                console.log('[withAndroidDebugConfig] Injected debug properties into buildTypes.debug');
            } else {
                // 如果 buildTypes 里没有 debug 块（罕见），尝试在开头创建
                buildGradle = buildGradle.replace(
                    /buildTypes\s*{/,
                    `buildTypes {
        debug {
            applicationIdSuffix ".debug"
            resValue "string", "app_name", "Nexara (Dev)"
            signingConfig signingConfigs.debug
        }`
                );
                console.log('[withAndroidDebugConfig] Created new debug block in buildTypes');
            }
        }

        config.modResults.contents = buildGradle;
        return config;
    });
};

module.exports = withAndroidDebugConfig;
