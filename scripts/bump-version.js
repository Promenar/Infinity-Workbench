const fs = require('fs');
const path = require('path');

const appJsonPath = path.resolve(__dirname, '../app.json');
const packageJsonPath = path.resolve(__dirname, '../package.json');

// Read files
const appJson = require(appJsonPath);
const packageJson = require(packageJsonPath);

// Get bump type
const type = process.argv[2]; // 'patch' or 'minor'
if (!['patch', 'minor'].includes(type)) {
    console.error('❌ Usage: node bump-version.js [patch|minor]');
    process.exit(1);
}

// Current Version
let [major, minor, patch] = appJson.expo.version.split('.').map(Number);
let currentVersion = appJson.expo.version;
let newVersion = '';

// Increment
if (type === 'minor') {
    minor++;
    patch = 0;
} else if (type === 'patch') {
    patch++;
}
newVersion = `${major}.${minor}.${patch}`;

// Increment Version Code
const currentCode = appJson.expo.android.versionCode || 1;
const newCode = currentCode + 1;

// Update app.json
appJson.expo.version = newVersion;
appJson.expo.android.versionCode = newCode;

// Update package.json
packageJson.version = newVersion;

// Update android/app/build.gradle
const buildGradlePath = path.resolve(__dirname, '../android/app/build.gradle');
if (fs.existsSync(buildGradlePath)) {
    let buildGradle = fs.readFileSync(buildGradlePath, 'utf8');

    // Update versionCode
    buildGradle = buildGradle.replace(/versionCode \d+/, `versionCode ${newCode}`);

    // Update versionName
    buildGradle = buildGradle.replace(/versionName "[^"]+"/, `versionName "${newVersion}"`);

    fs.writeFileSync(buildGradlePath, buildGradle);
    console.log(`✅ Updated build.gradle: ${newVersion} (${newCode})`);
}

// Write files
fs.writeFileSync(appJsonPath, JSON.stringify(appJson, null, 2) + '\n');
fs.writeFileSync(packageJsonPath, JSON.stringify(packageJson, null, 2) + '\n');

console.log(`\n✅ Version Bumped: ${currentVersion} -> ${newVersion}`);
console.log(`✅ Version Code: ${currentCode} -> ${newCode}`);
console.log(`\n🚀 Ready to build! Run: cd android && ./gradlew assembleRelease\n`);
