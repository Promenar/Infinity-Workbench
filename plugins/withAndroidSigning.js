const { withAppBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo Config Plugin to inject release signing configuration.
 * 修正逻辑：即使 signingConfigs 已存在，也要确保其中包含 release 块。
 */
const withAndroidSigning = (config) => {
    return withAppBuildGradle(config, (config) => {
        let buildGradle = config.modResults.contents;

        // 1. 注入 release signingConfigs
        // 检查 signingConfigs 内部是否已经有了 release 块
        const hasReleaseInSigning = /signingConfigs\s*{[\s\S]*?release\s*{/.test(buildGradle);

        if (!hasReleaseInSigning) {
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
                // 如果是开发环境（如根目录），secure_env 可能不存在
                // 为防止 Gradle 评估失败，必须定义一个占位符或回退到 debug
                println "Secure env not found at $secureEnv, falling back to debug signing for release block."
                storeFile file('debug.keystore')
                storePassword 'android'
                keyAlias 'androiddebugkey'
                keyPassword 'android'
            }
        }`;

            // 尝试插入到 signingConfigs 之后
            if (buildGradle.includes('signingConfigs {')) {
                buildGradle = buildGradle.replace(
                    /signingConfigs\s*{/,
                    `signingConfigs {\n${signingConfigStr}`
                );
            }
        }

        // 2. 更新 release buildType 引用
        // 确保 buildTypes 里的 release 使用了 signingConfigs.release
        const releaseBuildTypePattern = /release\s*{[\s\S]*?signingConfig\s*signingConfigs\.debug/;
        if (releaseBuildTypePattern.test(buildGradle)) {
            buildGradle = buildGradle.replace(
                releaseBuildTypePattern,
                (match) => match.replace('signingConfigs.debug', 'signingConfigs.release')
            );
        }

        config.modResults.contents = buildGradle;
        return config;
    });
};

module.exports = withAndroidSigning;
