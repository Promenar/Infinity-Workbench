const { withAndroidManifest, withMainActivity } = require('@expo/config-plugins');

/**
 * 启用 Android 广色域渲染能力
 * 1. 在 AndroidManifest.xml 的 application 和 activity 层级注入 colorMode
 * 2. 在 MainActivity.kt 中注入运行时显存模式切换
 */
const withWideColorGamut = (config) => {
    // 1. 修改 AndroidManifest.xml
    config = withAndroidManifest(config, async (config) => {
        const androidManifest = config.modResults;
        const mainApplication = androidManifest.manifest.application[0];

        // 应用级
        mainApplication.$['android:colorMode'] = 'wideColorGamut';

        // Activity 级 (确保 MainActivity 也生效)
        if (mainApplication.activity) {
            const mainActivity = mainApplication.activity.find(
                (activity) => activity.$['android:name'] === '.MainActivity'
            );
            if (mainActivity) {
                mainActivity.$['android:colorMode'] = 'wideColorGamut';
            }
        }

        return config;
    });

    // 2. 修改 MainActivity.kt (注入运行时强制开启代码)
    config = withMainActivity(config, async (config) => {
        if (config.modResults.language === 'kt') {
            let content = config.modResults.contents;

            // 导入必要的类
            if (!content.includes('import android.os.Build')) {
                content = content.replace(/package .*/, '$&\n\nimport android.os.Build');
            }
            if (!content.includes('import android.content.pm.ActivityInfo')) {
                content = content.replace(/import android.os.Build/, '$&\nimport android.content.pm.ActivityInfo');
            }

            // 在 onCreate 中注入模式切换
            const onCreatePattern = /super\.onCreate\(null\)/;
            const injection = `
    // 启用广色域渲染 (HDR/P3 Support)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
    }
    super.onCreate(null)`;

            if (content.includes('super.onCreate(null)') && !content.includes('window.colorMode')) {
                content = content.replace(onCreatePattern, injection);
            }

            config.modResults.contents = content;
        }
        return config;
    });

    return config;
};

module.exports = withWideColorGamut;
