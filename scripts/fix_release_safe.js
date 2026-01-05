const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(file)) {
    console.error("File not found");
    process.exit(1);
}

let content = fs.readFileSync(file, 'utf8');

// 1. Ensure signingConfigs.release DEFINITION exists
const releaseConfigDef = `
        release {
            println "DEBUG: MYAPP_UPLOAD_STORE_FILE exists? " + project.hasProperty('MYAPP_UPLOAD_STORE_FILE')
            if (project.hasProperty('MYAPP_UPLOAD_STORE_FILE')) {
                storeFile file(MYAPP_UPLOAD_STORE_FILE)
                storePassword MYAPP_UPLOAD_STORE_PASSWORD
                keyAlias MYAPP_UPLOAD_KEY_ALIAS
                keyPassword MYAPP_UPLOAD_KEY_PASSWORD
            }
        }`;

if (!content.includes('DEBUG: MYAPP_UPLOAD_STORE_FILE exists?')) {
    if (content.includes('signingConfigs {')) {
        content = content.replace('signingConfigs {', `signingConfigs {${releaseConfigDef}`);
        console.log("✅ Injected signingConfigs.release definition.");
    } else {
        console.error("❌ signingConfigs block not found!");
    }
}

// 2. Replace/Update buildTypes.release block
// We want to force it to be clean and R8 disabled.
// Expected usage:
// buildTypes {
//    debug { ... }
//    release { ... }
// }

// Regex to find the release block inside buildTypes
// Matches "release {" followed by non-closing-brace chars, then "}"
const releaseBlockRegex = /release\s*\{[^}]+\}/;

const desiredReleaseBlock = `release {
            signingConfig signingConfigs.release
            minifyEnabled false
            shrinkResources false
            proguardFiles getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"
        }`;

if (releaseBlockRegex.test(content)) {
    content = content.replace(releaseBlockRegex, desiredReleaseBlock);
    console.log("✅ Replaced release block with Safe + R8 Disabled Config.");
} else {
    // If not found (unlikely in prebuild), try to append to buildTypes {
    if (content.includes('buildTypes {')) {
        content = content.replace('buildTypes {', `buildTypes {\n        ${desiredReleaseBlock}`);
        console.log("✅ Appended release block to buildTypes.");
    } else {
        console.error("❌ buildTypes block not found!");
    }
}

fs.writeFileSync(file, content);
console.log("🎉 fix_release_safe.js complete.");
