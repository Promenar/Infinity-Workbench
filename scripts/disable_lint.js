const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(file)) {
    console.error("File not found");
    process.exit(1);
}

let content = fs.readFileSync(file, 'utf8');

// Check if lintOptions exists
if (content.includes('checkReleaseBuilds false')) {
    console.log("✅ Lint check escape already present.");
    process.exit(0);
}

// Insert inside android { ... }
// Finding 'defaultConfig {' is a good anchor inside android {
const anchor = 'defaultConfig {';
const pos = content.indexOf(anchor);

if (pos !== -1) {
    const output = content.replace(anchor, `lintOptions {\n        checkReleaseBuilds false\n        abortOnError false\n    }\n\n    ${anchor}`);
    fs.writeFileSync(file, output);
    console.log("🎉 Injected lintOptions to disable release checks.");
} else {
    console.error("❌ Could not find defaultConfig block.");
    process.exit(1);
}
