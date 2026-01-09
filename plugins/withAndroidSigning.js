const { withAppBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo Config Plugin to inject release signing configuration.
 */
const withAndroidSigning = (config) => {
    return withAppBuildGradle(config, (config) => {
        let buildGradle = config.modResults.contents;

        // 1. Inject release signingConfigs
        if (!buildGradle.includes('signingConfigs {') || !buildGradle.includes('release {')) {
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
                 println "Secure env not found at $secureEnv"
            }
        }`;

            // Insert into signingConfigs { ... }
            buildGradle = buildGradle.replace(
                /signingConfigs\s*{/,
                `signingConfigs {\n${signingConfigStr}`
            );
        }

        // 2. Update release buildType to use the new signingConfig
        const releasePattern = /release\s*{\s*\/\/\s*Caution![\s\S]*?signingConfig\s*signingConfigs\.debug/;
        if (releasePattern.test(buildGradle)) {
            buildGradle = buildGradle.replace(
                releasePattern,
                (match) => match.replace('signingConfigs.debug', 'signingConfigs.release')
            );
        } else {
            // Fallback for different templates
            buildGradle = buildGradle.replace(
                /release\s*{\s*signingConfig\s*signingConfigs\.debug/,
                'release {\n            signingConfig signingConfigs.release'
            );
        }

        config.modResults.contents = buildGradle;
        return config;
    });
};

module.exports = withAndroidSigning;
