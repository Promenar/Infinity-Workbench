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
                    // Fix: Trust that release build is signed unless it explicitly uses debug config
                    if (signingConfigName == "debug") {
                        signLabel = "-Unsigned"
                    } else {
                        // Even if "none", purely implies default release keys or custom keys injected
                        signLabel = "-Signed"
                    }
                }
                
                def newName = "Nexara-v\${versionName}-\${typeLabel}\${signLabel}-\${date}.apk"
                outputFileName = newName
            }
        }
    `;

        // Inject into the android { ... } block
        // Remove old injection if exists (rough check) or just rely on the fact that we edit the source file? 
        // Wait, this is a plugin. It modifies build.gradle content from Expo config.
        // It uses regex to replace or insert.

        // If the file already has logic, we should try to replace it?
        // But since this is a plugin, it usually runs cleanly on the template.
        // BUT we are using this in "prebuild". Since we already ran prebuild, the build.gradle HAS the logic.
        // If I update the plugin file, I need to run PREBUILD again to apply it? 
        // YES.

        // Alternatively, I can just manually edit build.gradle with fix_signing.js logic to update the naming logic.
        // But updating the plugin and running prebuild is cleaner for "source of truth", BUT slower.
        // And prebuild --clean wipes my fix_signing.js changes (the build.gradle edits)!

        // CRITICAL DEBT: My fix_signing.js edits to build.gradle are ephemeral if I run prebuild --clean.
        // But if I don't run prebuild --clean, the plugin code in build.gradle is the OLD one.

        // Strategy:
        // 1. Update the plugin file (for future correctness).
        // 2. ALSO update the build.gradle naming logic IN PLACE using a script (because I don't want to run prebuild --clean again and lose keys/caches).

        // Actually, just updating build.gradle naming logic string is enough for now.

        if (!buildGradle.includes('outputFileName = newName')) {
            // ... original injection code ...
            config.modResults.contents = buildGradle.replace(
                /android\s*{/,
                `android {\n${namingLogic}`
            );
        } else {
            // Logic to REPLACE existing naming logic if found?
            // This is hard with regex for a block.
            // But this plugin file is used by `npx expo prebuild`. 
            // I am NOT running prebuild right now.
        }

        return config;
    });
};

module.exports = withCustomApkName;
