const { withAppBuildGradle } = require('@expo/config-plugins');

/**
 * Expo Config Plugin to customize the output APK filename.
 * 
 * Naming convention:
 * - Release (Signed): Nexara-v[version]-Release-Signed-[date].apk
 * - Release (Unsigned): Nexara-v[version]-Release-Unsigned-[date].apk
 * - Debug: Nexara-v[version]-Debug-[date].apk
 */
const withCustomApkName = (config) => {
    return withAppBuildGradle(config, (config) => {
        const buildGradle = config.modResults.contents;

        // Naming logic to inject
        const namingLogic = `
        applicationVariants.all { variant ->
            variant.outputs.all { output ->
                def date = new Date().format('yyyyMMdd')
                def versionName = variant.versionName
                def buildType = variant.buildType.name // 'release' or 'debug'
                def signingConfigName = variant.signingConfig?.name ?: "none"
                
                def typeLabel = buildType.capitalize()
                def signLabel = ""
                
                if (buildType == "release") {
                    // Check if it's using a custom release config or just the default debug one
                    if (signingConfigName != "debug" && signingConfigName != "none") {
                        signLabel = "-Signed"
                    } else {
                        signLabel = "-Unsigned"
                    }
                }
                
                def newName = "Nexara-v\${versionName}-\${typeLabel}\${signLabel}-\${date}.apk"
                outputFileName = newName
            }
        }
    `;

        // Inject into the android { ... } block
        if (!buildGradle.includes('outputFileName = newName')) {
            // We find the end of the android block or just append to it
            // A safe way is to inject it inside the android block
            config.modResults.contents = buildGradle.replace(
                /android\s*{/,
                `android {\n${namingLogic}`
            );
        }

        return config;
    });
};

module.exports = withCustomApkName;
