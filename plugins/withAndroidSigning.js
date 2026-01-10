const { withAppBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo Config Plugin to inject release signing configuration.
 * 修正逻辑：
 * 1. 显式区分 signingConfigs 和 buildTypes。
 * 2. 移除调试日志以保持整洁。
 */
const withAndroidSigning = (config) => {
    return withAppBuildGradle(config, (config) => {
        let buildGradle = config.modResults.contents;

        // 1. 注入 release signingConfigs
        const signingConfigsMatch = buildGradle.match(/signingConfigs\s*{([\s\S]*?)\n\s*}/);
        const signingConfigsContent = signingConfigsMatch ? signingConfigsMatch[1] : '';
        const hasReleaseInSigning = /\brelease\s*{/.test(signingConfigsContent);

        if (!hasReleaseInSigning && signingConfigsMatch) {
            const signingConfigStr = `
        release {
            def secureEnv = file("../../../../secure_env/secure.properties")
            if (secureEnv.exists()) {
                def props = new Properties()
                secureEnv.withInputStream { props.load(it) }
                storeFile file("../../../../secure_env/promenar.keystore")
                storePassword props.getProperty("KEYSTORE_PASSWORD")
                keyAlias props.getProperty("KEY_ALIAS")
                keyPassword props.getProperty("KEY_PASSWORD")
            } else {
                println "Secure env not found at $secureEnv, falling back to debug signing for release block."
                storeFile file('debug.keystore')
                storePassword 'android'
                keyAlias 'androiddebugkey'
                keyPassword 'android'
            }
        }`;

            // 插入到 signingConfigs { 之后
            buildGradle = buildGradle.replace(
                /signingConfigs\s*{/,
                `signingConfigs {\n${signingConfigStr}`
            );
        }

        // 2. 更新 release buildType 引用
        // 提取 buildTypes 块
        const buildTypesMatch = buildGradle.match(/buildTypes\s*{([\s\S]*?)\n\s*}/);
        if (buildTypesMatch) {
            let buildTypesContent = buildTypesMatch[1];
            // 确保 release buildType 使用了 signingConfigs.release
            if (buildTypesContent.includes('release {') && buildTypesContent.includes('signingConfigs.debug')) {
                const updatedBuildTypes = buildTypesContent.replace(
                    /release\s*{[\s\S]*?signingConfig\s*signingConfigs\.debug/,
                    (match) => match.replace('signingConfigs.debug', 'signingConfigs.release')
                );
                buildGradle = buildGradle.replace(buildTypesContent, updatedBuildTypes);
            }
        }

        config.modResults.contents = buildGradle;
        return config;
    });
};

module.exports = withAndroidSigning;
