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
        console.log('[withAndroidSigning] Processing build.gradle...');

        // 1. 注入 release signingConfigs
        // 提取 signingConfigs 块的内容
        const signingConfigsMatch = buildGradle.match(/signingConfigs\s*{([\s\S]*?)\n\s*}/);
        const signingConfigsContent = signingConfigsMatch ? signingConfigsMatch[1] : '';
        const hasReleaseInSigning = /\brelease\s*{/.test(signingConfigsContent);

        console.log('[withAndroidSigning] hasReleaseInSigning:', hasReleaseInSigning);

        if (!hasReleaseInSigning) {
            console.log('[withAndroidSigning] Injecting release signing config...');
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

            if (signingConfigsMatch) {
                // 插入到 signingConfigs { 之后
                buildGradle = buildGradle.replace(
                    /signingConfigs\s*{/,
                    `signingConfigs {\n${signingConfigStr}`
                );
                console.log('[withAndroidSigning] Injection successful.');
            } else {
                console.log('[withAndroidSigning] Error: signingConfigs { block not found!');
            }
        }

        // 2. 更新 release buildType 引用
        const releaseBuildTypePattern = /release\s*{[\s\S]*?signingConfig\s*signingConfigs\.debug/;
        console.log('[withAndroidSigning] releaseBuildTypePattern match:', releaseBuildTypePattern.test(buildGradle));

        if (releaseBuildTypePattern.test(buildGradle)) {
            buildGradle = buildGradle.replace(
                releaseBuildTypePattern,
                (match) => match.replace('signingConfigs.debug', 'signingConfigs.release')
            );
            console.log('[withAndroidSigning] Updated release buildType to use signingConfigs.release');
        }

        config.modResults.contents = buildGradle;
        return config;
    });
};

module.exports = withAndroidSigning;
