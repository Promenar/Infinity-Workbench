const { withAppBuildGradle } = require('@expo/config-plugins');

const withAndroidDebugConfig = (config) => {
    return withAppBuildGradle(config, (config) => {
        const buildGradle = config.modResults.contents;

        // Check if already applied to avoid duplication
        if (buildGradle.includes('applicationIdSuffix ".debug"')) {
            return config;
        }

        const debugConfig = `
        debug {
            signingConfig signingConfigs.debug
            applicationIdSuffix ".debug"
            resValue "string", "app_name", "Nexara (Dev)"
        }`;

        // Replace the default debug block
        // The default block usually looks like:
        // debug {
        //     signingConfig signingConfigs.debug
        // }
        // We use a regex that matches loose whitespace.

        const pattern = /debug\s*{\s*signingConfig\s*signingConfigs\.debug\s*}/;

        if (pattern.test(buildGradle)) {
            config.modResults.contents = buildGradle.replace(pattern, debugConfig);
        } else {
            // Fallback: If the regex doesn't match (maybe structure changed), 
            // try to insert inside buildTypes { ... } but specifically for debug if it exists loosely
            // Or just append it. But creating a new debug block might fail if one exists.
            // Let's try a broader match or verify file content first in next steps if this fails.
            // For now, assuming standard Expo prebuild template.
            console.warn("Could not find standard debug block to replace. Attempting to insert properties into existing debug block.");

            // Try to find "debug {" and insert after it
            config.modResults.contents = buildGradle.replace(
                /debug\s*{/,
                `debug {\n            applicationIdSuffix ".debug"\n            resValue "string", "app_name", "Nexara (Dev)"`
            );
        }

        return config;
    });
};

module.exports = withAndroidDebugConfig;
