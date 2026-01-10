const { withAndroidManifest, withProjectBuildGradle } = require('@expo/config-plugins');

/**
 * 1. 在 AndroidManifest.xml 中注入 ForegroundService 声明
 * 2. 在项目根 build.gradle 中注入 Notifee 本地 Maven 仓库地址
 */
const withForegroundService = (config) => {
    // 1. 修改 AndroidManifest.xml
    config = withAndroidManifest(config, async (config) => {
        const androidManifest = config.modResults;

        if (!androidManifest.manifest['uses-permission']) {
            androidManifest.manifest['uses-permission'] = [];
        }

        const permissions = [
            'android.permission.FOREGROUND_SERVICE',
            'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
        ];

        permissions.forEach((permission) => {
            const hasPermission = androidManifest.manifest['uses-permission'].some(
                (props) => props.$['android:name'] === permission
            );

            if (!hasPermission) {
                androidManifest.manifest['uses-permission'].push({
                    $: { 'android:name': permission },
                });
            }
        });

        const mainApplication = androidManifest.manifest.application[0];
        const serviceName = 'app.notifee.core.ForegroundService';

        let service = mainApplication.service?.find(
            (s) => s.$['android:name'] === serviceName
        );

        if (!service) {
            if (!mainApplication.service) mainApplication.service = [];
            service = { $: { 'android:name': serviceName } };
            mainApplication.service.push(service);
        }

        service.$['android:foregroundServiceType'] = 'dataSync';

        return config;
    });

    // 2. 修改根 build.gradle 注入 Notifee 仓库
    config = withProjectBuildGradle(config, (config) => {
        let buildGradle = config.modResults.contents;

        const notifeeRepo = `maven { url "$rootDir/../node_modules/@notifee/react-native/android/libs" }`;

        if (!buildGradle.includes('@notifee/react-native/android/libs')) {
            // 在 allprojects { repositories { ... } } 中注入
            buildGradle = buildGradle.replace(
                /allprojects\s*{[\s\S]*?repositories\s*{/,
                `$&\n        ${notifeeRepo}`
            );
        }

        config.modResults.contents = buildGradle;
        return config;
    });

    return config;
};

module.exports = withForegroundService;
