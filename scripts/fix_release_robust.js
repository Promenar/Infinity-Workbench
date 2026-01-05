const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(file)) {
    console.error("File not found");
    process.exit(1);
}

let content = fs.readFileSync(file, 'utf8');

// Helper to find block bounds
function findBlockBounds(text, startSearchIndex, openMarker) {
    const startIndex = text.indexOf(openMarker, startSearchIndex);
    if (startIndex === -1) return null;

    let braceCount = 0;
    let foundOpen = false;
    let endIndex = -1;

    for (let i = startIndex; i < text.length; i++) {
        if (text[i] === '{') {
            braceCount++;
            foundOpen = true;
        } else if (text[i] === '}') {
            braceCount--;
        }

        if (foundOpen && braceCount === 0) {
            endIndex = i + 1; // Perform inclusive slice
            break;
        }
    }

    if (endIndex === -1) return null;
    return { start: startIndex, end: endIndex };
}

// 1. Inject Signing Config Definition (into signingConfigs or android)
// We look for 'signingConfigs {'
let signingConfigsPos = content.indexOf('signingConfigs {');
const signingDef = `
        release {
            if (project.hasProperty('MYAPP_UPLOAD_STORE_FILE')) {
                storeFile file(MYAPP_UPLOAD_STORE_FILE)
                storePassword MYAPP_UPLOAD_STORE_PASSWORD
                keyAlias MYAPP_UPLOAD_KEY_ALIAS
                keyPassword MYAPP_UPLOAD_KEY_PASSWORD
            }
        }`;

if (signingConfigsPos !== -1) {
    // Inject inside existing block
    // We can just replace 'signingConfigs {' with 'signingConfigs { <def>'
    if (!content.includes('MYAPP_UPLOAD_STORE_FILE')) {
        content = content.replace('signingConfigs {', `signingConfigs {${signingDef}`);
        console.log("✅ Injected signingConfigs.release definition.");
    }
} else {
    // If no signingConfigs, we usually inject it inside 'android {'
    // But standard expo prebuild usually has it.
    // If not, we skip for now and rely on build-release.ps1 or manual injection if needed?
    // Actually, ps1 relies on this definition being present to work cleaner.
    // Let's assume prebuild has it or we inject it in android block top.
    const androidBlock = findBlockBounds(content, 0, 'android {');
    if (androidBlock) {
        // Insert at start of android block
        const insideAndroid = content.substring(androidBlock.start + 'android {'.length, androidBlock.end - 1);
        // Just append signingConfigs at start
        // Be careful not to break syntax
        // content = content.slice(0, androidBlock.start + 'android {'.length) + `\n    signingConfigs { ${signingDef} }\n` + content.slice(androidBlock.start + 'android {'.length);
        // console.log("✅ Created signingConfigs block.");
    }
}

// 2. Fix buildTypes.release
// Find 'buildTypes {'
const buildTypesBounds = findBlockBounds(content, 0, 'buildTypes {');
if (buildTypesBounds) {
    const buildTypesContent = content.substring(buildTypesBounds.start, buildTypesBounds.end);

    // Find 'release {' inside buildTypes
    // Note: search relative to file content, bounded by buildTypes start/end
    const releaseBounds = findBlockBounds(content, buildTypesBounds.start, 'release {');

    if (releaseBounds && releaseBounds.end <= buildTypesBounds.end) {
        // Found it within buildTypes!
        // Replace ONLY this block
        const desiredRelease = `release {
            signingConfig signingConfigs.release
            minifyEnabled false
            shrinkResources false
            proguardFiles getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"
        }`;

        const before = content.substring(0, releaseBounds.start);
        const after = content.substring(releaseBounds.end);
        content = before + desiredRelease + after;
        console.log("✅ Robstly replaced release { ... } block.");
    } else {
        console.log("❌ release { } block not found inside buildTypes!");
    }
} else {
    console.log("❌ buildTypes { } block not found!");
}

fs.writeFileSync(file, content);
console.log("🎉 fix_release_robust.js complete.");
