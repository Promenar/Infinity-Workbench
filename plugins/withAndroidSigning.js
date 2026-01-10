const { withAppBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo Config Plugin to inject release signing configuration.
 * 修正逻辑：使用更可靠的正则表达式替换 release buildType 的签名配置。
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

            buildGradle = buildGradle.replace(
                /signingConfigs\s*{/,
                `signingConfigs {\n${signingConfigStr}`
            );
        }

        // 2. 更新 release buildType 引用
        // 查找 buildTypes 里的 release { ... } 块，并替换其 signingConfig
        const buildTypesMatch = buildGradle.match(/buildTypes\s*{([\s\S]+)}\s*packagingOptions/);
        if (buildTypesMatch) {
            const oldBuildTypes = buildTypesMatch[1];
            const updatedBuildTypes = oldBuildTypes.replace(
                /(release\s*{[\s\S]*?signingConfig\s*)signingConfigs\.debug/,
                '$1signingConfigs.release'
            );
            buildGradle = buildGradle.replace(oldBuildTypes, updatedBuildTypes);
        }

        config.modResults.contents = buildGradle;
        return config;
    });
};

module.exports = withAndroidSigning;
